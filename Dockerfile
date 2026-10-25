# ============================================================================
# 纽启新生 (NQX) — 后端镜像
# ----------------------------------------------------------------------------
# 多阶段构建：
#   构建阶段用 Maven + JDK 17 打包，运行阶段只保留 JRE 与 jar。
#   这样最终镜像不含 Maven 仓库与源码，体积从 1GB+ 降到 200MB 左右，
#   同时也减少攻击面（镜像里没有编译工具链）。
#
# 构建：docker build -t nqx-backend:1.0.0 .
# 运行：见 docker-compose.yml
# ============================================================================

# ============================================================================
# 阶段一：构建
# ============================================================================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# ---------------------------------------------------------------------------
# 先只复制 POM 并预热依赖，再复制源码。
# 这样只要 POM 没变，源码改动就不会导致依赖重新下载 ——
# Docker 的层缓存是按指令内容命中的，源码一变更，其后的所有层都会失效。
# 把"下载依赖"这一步单独成层，能把日常构建从几分钟压到几十秒。
#
# 同时注入阿里云镜像：容器内从 Maven 中央仓库拉 Spring Boot 全套依赖
# 在国内网络下会慢到不可接受。
# ---------------------------------------------------------------------------
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY nqx-common/pom.xml nqx-common/
COPY nqx-pojo/pom.xml nqx-pojo/
COPY nqx-mapper/pom.xml nqx-mapper/
COPY nqx-service/pom.xml nqx-service/
COPY nqx-web/pom.xml nqx-web/

RUN mvn -B -ntp dependency:go-offline -DskipTests || true

# 再复制源码与配置
COPY nqx-common/src nqx-common/src
COPY nqx-pojo/src nqx-pojo/src
COPY nqx-mapper/src nqx-mapper/src
COPY nqx-service/src nqx-service/src
COPY nqx-web/src nqx-web/src

RUN mvn -B -ntp clean package -DskipTests

# ============================================================================
# 阶段一b：构建管理后台（Vue 3 + Element Plus）
# ============================================================================
# 【为什么把前端打进同一个镜像】
#
# 也可以把 dist 单独丢到「静态资源托管」，但那样有个致命问题：
# 静态托管是纯 CDN，**它不会把 /admin/auth/login 转发给后端容器**。
# 前端只能直连后端域名 → 跨域 → 还得配 CORS；而 history 路由的深链还会 404。
#
# 打进同一个镜像就都解决了：同源、无跨域、一个端口、一个域名。
#
# 【为什么用 node:20-alpine 而不是装在本机再 COPY 进来】
# 构建可复现 —— 不依赖开发者本机装了什么版本的 Node。
# ============================================================================
FROM node:20-alpine AS ui-builder

WORKDIR /ui

# 先只拷依赖清单再安装：只要清单没变，这一层就能命中缓存，
# 改前端源码时不必重装依赖
COPY nqx-ui/package.json nqx-ui/package-lock.json ./

# 有 lock 文件就用 npm ci（严格按 lock 安装，结果可复现）；
# 万一 lock 缺失则退回 npm install，保证构建不会因此直接失败
RUN if [ -f package-lock.json ]; then \
        npm ci --no-audit --no-fund; \
    else \
        npm install --no-audit --no-fund; \
    fi

COPY nqx-ui/ ./

# build 脚本里包含 `vue-tsc --noEmit`（类型检查）——
# 类型错误会让镜像构建失败，这是刻意的：宁可构建失败，
# 也不要打出一个"能启动但页面报错"的镜像。
RUN npm run build

# ============================================================================
# 阶段二：运行
# ============================================================================
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="nqx" \
      description="纽启新生 - 高校实验室纽扣电池回收与采购管理平台"

# 时区：容器默认 UTC，不设置的话日志时间和数据库时间会差 8 小时，
# 排查问题时会非常困惑
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# ---------------------------------------------------------------------------
# 以非 root 用户运行。
# 容器内 root 与宿主机 root 在权限模型上并不完全隔离，
# 一旦应用被攻破（如反序列化漏洞），攻击者可直接获得容器内最高权限，
# 配合内核漏洞更容易逃逸到宿主机。这是容器安全的基本要求。
# ---------------------------------------------------------------------------
RUN addgroup -S nqx && adduser -S nqx -G nqx

WORKDIR /app

# 上传目录、日志目录、前端静态目录
RUN mkdir -p /app/uploads /app/logs /app/static && chown -R nqx:nqx /app

COPY --from=builder --chown=nqx:nqx /build/nqx-web/target/nqx.jar /app/app.jar

# ---------------------------------------------------------------------------
# 管理后台静态文件 → /app/static
#
# 应用侧通过 spring.web.resources.static-locations=file:/app/static/ 提供这些文件，
# 并由 SpaFallbackFilter 把 /order/recycle 这类前端路由回退到 index.html
# （Vue 用的是 history 模式，刷新深链时必须回退，否则 404）。
#
# 前提：环境变量 SPA_ENABLED=true（application-prod.yml 里默认 true）
# ---------------------------------------------------------------------------
COPY --from=ui-builder --chown=nqx:nqx /ui/dist /app/static

USER nqx

EXPOSE 8080

# ---------------------------------------------------------------------------
# JVM 参数说明：
#   -XX:MaxRAMPercentage=75.0  按容器内存上限的 75% 设置堆，而不是用固定 -Xmx。
#                              容器里 JVM 默认读不到 cgroup 限制（老版本会读成宿主机内存），
#                              按百分比设置可避免堆开得过大被 OOM Killer 干掉。
#   -XX:+UseG1GC               G1 在中等堆大小下停顿更可控
#   -Djava.security.egd=...    加快 SecureRandom 初始化，避免启动时卡住
#   -Duser.timezone            与 TZ 保持一致，保证 LocalDateTime 正确
# ---------------------------------------------------------------------------
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs \
    -Djava.security.egd=file:/dev/./urandom \
    -Duser.timezone=Asia/Shanghai \
    -Dfile.encoding=UTF-8"

# 健康检查直接用 Actuator 端点。alpine 自带 busybox wget，无需额外安装 curl
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

# 用 sh -c 是为了让 $JAVA_OPTS 能被 shell 展开。
# 若写成 ENTRYPOINT ["java", "$JAVA_OPTS", ...] 这种 exec 形式，
# $JAVA_OPTS 不会被展开，会被当成一个名为 "-XX:MaxRAMPercentage..." 的奇怪参数。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
