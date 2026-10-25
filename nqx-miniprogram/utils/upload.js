/**
 * ============================================================================
 *  图片上传工具
 * ============================================================================
 *
 *  【要解决什么问题】
 *
 *  小程序里 `wx.chooseImage` / `wx.chooseAvatar` 返回的都是**临时文件路径**
 *  （`wxfile://tmp_xxx` 或 `http://tmp/xxx.jpeg`）。它有两个致命性质：
 *
 *    1. 只存在于当前微信客户端会话，**重启小程序或换设备就失效**；
 *    2. 指向手机本地文件，服务端读不到。
 *
 *  项目里原来有 4 个地方直接把临时路径存进数据库或提交给后端：
 *
 *    · 登录页的「完善资料」头像
 *    · 个人资料编辑页的头像
 *    · 订单评价的晒图
 *    · 维修申请的凭证
 *
 *  表现都一样：**当天正常，第二天图片全挂，而且没有任何报错** ——
 *  接口返回 200、页面不报错，只是图片变成空白/裂图。
 *
 *  【为什么不走垫片】
 *
 *  页面里的请求都走 `wx.cloud.callContainer`，垫片把它转成 `wx.request`（JSON）。
 *  但上传是 **multipart/form-data**，`wx.request` 发不了文件，必须用
 *  `wx.uploadFile`。所以这里直接打后端地址，不经过垫片。
 *
 *  【返回值有两种形态，取决于后端配置】
 *
 *  后端的 `nqx.upload.type` 决定文件存在哪、返回什么：
 *
 *    · `local`（本地开发）→ 存 ./uploads，返回**相对路径** `/files/xxx/yyy.jpg`
 *    · `cos`（生产/云托管）→ 存腾讯云 COS，返回**绝对地址**
 *      `https://<bucket>.cos.<region>.myqcloud.com/uploads/xxx/yyy.jpg`
 *
 *  页面两种都能直接用：`request.js` 的 `rewriteAssetUrls` 只给 `/files/` 开头的值
 *  补域名，绝对地址会原样通过。这样从本地切到云存储时，**小程序代码一行都不用改**。
 *
 *  【上传前会先压缩】
 *
 *  手机照片动辄几 MB，直接传既慢又可能被网关拒掉。超过 300KB 的图会先用
 *  `wx.compressImage` 压到 quality=72，压缩失败则静默回退原图（见 compressIfNeeded）。
 * ============================================================================
 */

const env = require('./env');

/** 单张图片大小上限（字节）。后端也有校验，这里提前拦住能给更快的反馈 */
const MAX_SIZE = 10 * 1024 * 1024;

/**
 * 压缩阈值（字节）。超过这个大小才压 —— 小图（图标、截图）压了没收益，
 * 反而可能因为重新编码而变糊。
 */
const COMPRESS_THRESHOLD = 300 * 1024;

/**
 * 上传前压缩图片。
 *
 * <h3>为什么必须压</h3>
 *
 * 手机拍的照片动辄 3~8MB，直接上传有三个问题：
 *
 *   1. **云托管网关有限制**：微信云托管的 `callContainer` 请求体上限是 100KiB，
 *      公网网关的大小上限也不高。几 MB 的照片很容易被直接拒掉，
 *      而前端只看到一个笼统的"上传失败"。
 *   2. **慢**：4G 下传 5MB 要好几秒，用户会以为卡死了。
 *   3. **贵**：COS 按存储量计费，压缩能把体积降到 1/10。
 *
 * <h3>为什么失败要"静默回退原图"</h3>
 *
 * `wx.compressImage` 在个别机型/格式上会失败（比如某些 HEIC 转码）。
 * 这时候直接放弃上传是错误的 —— 压缩只是优化，不是必需步骤。
 * 所以失败就用原图继续，只是打一条日志。
 *
 * 注意：压缩后得到的是**新的临时路径**，扩展名可能从 png 变成 jpg。
 * 后端按扩展名白名单校验，所以这里要确保回退路径也是合法的图片扩展名。
 */
function compressIfNeeded(filePath) {
  return new Promise((resolve) => {
    // 拿不到文件信息就直接用原图，不值得为"优化"冒失败风险
    wx.getFileInfo({
      filePath: filePath,
      success: (info) => {
        if (!info || info.size <= COMPRESS_THRESHOLD) {
          resolve(filePath);
          return;
        }
        wx.compressImage({
          src: filePath,
          quality: 72,
          success: (res) => {
            if (res && res.tempFilePath) {
              console.log('[upload] 已压缩：', Math.round(info.size / 1024), 'KB ->',
                res.tempFilePath);
              resolve(res.tempFilePath);
            } else {
              resolve(filePath);
            }
          },
          fail: (err) => {
            // 压缩失败不阻塞上传：原图照样能传，只是大一点
            console.warn('[upload] 压缩失败，改用原图', err);
            resolve(filePath);
          }
        });
      },
      fail: () => {
        resolve(filePath);
      }
    });
  });
}

/**
 * 上传一张图片，返回图片 URL。
 *
 * @param {string} filePath 本地临时文件路径（chooseImage / chooseAvatar 返回的）
 * @param {string} bizType  业务目录名，如 avatar / review / repair / recycle
 * @returns {Promise<string>}
 *   后端 `nqx.upload.type=local` 时返回相对路径 `/files/avatar/2026/10/19/xxx.jpg`；
 *   `type=cos`（生产/云托管）时返回绝对地址 `https://<bucket>.cos.<region>.myqcloud.com/uploads/...`。
 *   两种形态页面都能直接用，因为 `request.js` 只给 `/files/` 开头的值补前缀。
 */
function uploadImage(filePath, bizType) {
  return new Promise((resolve, reject) => {
    if (!filePath) {
      reject(new Error('没有选择文件'));
      return;
    }

    const token = wx.getStorageSync('token');
    if (!token) {
      // 上传接口要求登录。登录前不该调用它 —— 登录页的头像要等登录成功后再传。
      reject(new Error('请先登录'));
      return;
    }

    // 先压缩（仅在超过阈值时），再上传
    compressIfNeeded(filePath).then((finalPath) => {
      doUpload(finalPath, bizType, token).then(resolve).catch(reject);
    });
  });
}

/** 真正执行上传的部分（压缩之后调用） */
function doUpload(filePath, bizType, token) {
  return new Promise((resolve, reject) => {
    const url = env.API_BASE_URL.replace(/\/+$/, '') +
      '/app/file/upload?bizType=' + encodeURIComponent(bizType || 'common');

    wx.uploadFile({
      url: url,
      filePath: filePath,
      name: 'file',
      header: { Authorization: 'Bearer ' + token },
      success: (res) => {
        /*
         * ⚠️ wx.uploadFile 的 res.data 是**字符串**，不是对象。
         * 直接当对象用会得到 undefined，而且完全静默 —— 这是本 API
         * 与 wx.request 最大的行为差异，踩过一次就知道了。
         */
        let body = null;
        try {
          body = JSON.parse(res.data);
        } catch (e) {
          console.error('[upload] 响应不是合法 JSON：', res.data);
        }

        if (res.statusCode === 200 && body && body.code === 200 && body.data) {
          resolve(body.data);
        } else if (res.statusCode === 401) {
          reject(new Error('登录已过期，请重新登录'));
        } else {
          reject(new Error((body && body.msg) || '上传失败'));
        }
      },
      fail: (err) => {
        console.error('[upload] 上传失败：', err);
        reject(new Error('网络错误，上传失败'));
      }
    });
  });
}

/**
 * 批量上传，返回相对路径数组。
 *
 * 串行而不是 `Promise.all`：小程序同时发起多个上传容易触发并发限制，
 * 而且一次 3 张图串行上传的耗时用户完全能接受（每张通常 <1s）。
 * **稳定性优于理论上的并行收益。**
 */
async function uploadImages(filePaths, bizType) {
  const result = [];
  for (const p of filePaths || []) {
    result.push(await uploadImage(p, bizType));
  }
  return result;
}

/**
 * 把相对路径补成绝对地址（供 `<image src>` 使用）。
 *
 * 小程序里 `<image src="/files/x.jpg">` 会被当成**包内路径**去找，
 * 结果是空白且不报错。所以显示前必须补域名。
 */
function toAbsoluteUrl(url) {
  if (!url) {
    return '';
  }
  if (/^https?:\/\//i.test(url) || url.indexOf('wxfile://') === 0) {
    return url;
  }
  return env.API_BASE_URL.replace(/\/+$/, '') + url;
}

/** 判断一个地址是不是微信的临时文件路径（不可持久化） */
function isTempPath(url) {
  if (!url) {
    return false;
  }
  return url.indexOf('wxfile://') === 0 ||
    /^https?:\/\/tmp/i.test(url) ||
    url.indexOf('http://tmp/') === 0;
}

module.exports = {
  uploadImage,
  uploadImages,
  toAbsoluteUrl,
  isTempPath,
  MAX_SIZE
};
