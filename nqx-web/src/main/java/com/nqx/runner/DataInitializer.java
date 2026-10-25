package com.nqx.runner;

import cn.hutool.crypto.digest.BCrypt;
import com.nqx.mapper.SysUserMapper;
import com.nqx.mapper.SysUserRoleMapper;
import com.nqx.pojo.entity.SysUser;
import com.nqx.pojo.entity.SysUserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 启动数据初始化：确保系统里至少存在一个可登录的超级管理员。
 *
 * <p><b>为什么不把管理员账号直接写进 Flyway 的 V2__init_data.sql？</b>
 *
 * <p>因为密码必须经过 BCrypt 加盐哈希，而 BCrypt 每次加盐的结果都不同：
 * <ol>
 *   <li>把某个固定的哈希串写死在 SQL 里，等于把「默认弱口令」固化进了代码仓库，
 *       任何人都能从 Git 历史里查到，且无法通过修改配置更换；</li>
 *   <li>哈希值一旦写死，就无法做到「不同环境使用不同初始密码」；</li>
 *   <li>它会让 V2 脚本变得不可重放 —— 换一台机器部署时还得手工改密码字段。</li>
 * </ol>
 *
 * <p>因此改为运行时初始化：检测到 {@code sys_user} 为空时才创建，
 * 密码从配置项 {@code nqx.init.admin-password} 读取（生产环境用环境变量覆盖），
 * 并在创建后打印醒目提示要求立即修改。
 *
 * <p>实现 {@link ApplicationRunner} 而非 {@code @PostConstruct}：
 * 前者在容器完全就绪、Flyway 迁移完成后执行，能确保表结构已存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    /** 超级管理员角色 ID，对应 V2__init_data.sql 中插入的 SUPER_ADMIN */
    private static final Long SUPER_ADMIN_ROLE_ID = 1L;

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    @Value("${nqx.init.admin-username:admin}")
    private String adminUsername;

    @Value("${nqx.init.admin-password:Admin@123}")
    private String adminPassword;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        Long existing = sysUserMapper.selectCount(null);
        if (existing != null && existing > 0) {
            log.info("检测到已有 {} 个管理员账号，跳过初始化", existing);
            return;
        }

        SysUser admin = new SysUser();
        admin.setUsername(adminUsername);
        // BCrypt 自带随机盐，同样的明文每次哈希结果都不同，因此不存在彩虹表攻击面
        admin.setPassword(BCrypt.hashpw(adminPassword));
        admin.setRealName("超级管理员");
        admin.setStatus(1);
        admin.setRemark("系统初始化自动创建");
        admin.setCreateTime(LocalDateTime.now());
        admin.setUpdateTime(LocalDateTime.now());
        admin.setDeleted(0);
        sysUserMapper.insert(admin);

        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(admin.getId());
        userRole.setRoleId(SUPER_ADMIN_ROLE_ID);
        sysUserRoleMapper.insert(userRole);

        log.warn("""

                ============================================================
                  已初始化超级管理员账号，请登录后立即修改密码！
                    账号：{}
                    密码：{}
                  （密码已 BCrypt 加密存储，数据库中不可逆）
                ============================================================
                """, adminUsername, adminPassword);
    }
}
