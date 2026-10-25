import type { Directive, DirectiveBinding } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * 按钮级权限指令 v-permission。
 *
 * 用法：
 *   <el-button v-permission="'product:add'">新增</el-button>
 *   <el-button v-permission="['product:edit', 'product:add']">保存</el-button>  // 满足其一即可
 *
 * 实现选择「移除 DOM」而不是「禁用按钮」：
 * 禁用态会明晃晃地告诉用户"这里有个你不能用的功能"，
 * 而无权限用户根本不该知道这个入口存在。
 *
 * ⚠️ 前端隐藏只是体验优化，真正的安全边界是后端每个接口上的 @SaCheckPermission ——
 * 绕过前端直接调接口，后端照样会拒绝。
 */
function checkAndRemove(el: HTMLElement, binding: DirectiveBinding<string | string[]>) {
  const userStore = useUserStore()
  if (!userStore.hasPermission(binding.value)) {
    // 用 removeChild 而不是 display:none：让元素彻底离开文档流
    el.parentNode?.removeChild(el)
  }
}

export const permissionDirective: Directive<HTMLElement, string | string[]> = {
  // 只在 mounted 里判断一次。
  // 不放在 updated：权限码在一次登录会话内不会变，
  // 而 updated 在每次父组件更新时都会触发，元素早已被移除会导致重复操作。
  mounted: checkAndRemove
}
