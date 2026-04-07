/**
 * 防抖：在连续触发时只执行最后一次，适用于筛选、输入框查询等。
 * @param {Function} fn 要执行的函数
 * @param {number} ms 延迟毫秒数
 * @returns {Function} 防抖后的函数
 */
export function debounce(fn, ms = 300) {
  let timer = null
  return function (...args) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      fn.apply(this, args)
    }, ms)
  }
}

/**
 * 节流：在指定时间内最多执行一次，适用于刷新按钮、滚动等。
 * @param {Function} fn 要执行的函数
 * @param {number} ms 间隔毫秒数
 * @returns {Function} 节流后的函数
 */
export function throttle(fn, ms = 1000) {
  let last = 0
  let timer = null
  return function (...args) {
    const now = Date.now()
    const remaining = ms - (now - last)
    if (remaining <= 0) {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      last = now
      fn.apply(this, args)
    } else if (!timer) {
      timer = setTimeout(() => {
        last = Date.now()
        timer = null
        fn.apply(this, args)
      }, remaining)
    }
  }
}
