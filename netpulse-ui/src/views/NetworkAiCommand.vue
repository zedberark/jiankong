<template>
  <div class="page network-ai-page">
    <div class="layout-4">
      <!-- 左上：AI 配置需求 -->
      <section class="pane pane-tl">
        <h3 class="section-title">网络设备 AI 命令生成</h3>
        <p class="hint">选择厂商和设备类型，用自然语言描述配置需求，AI 生成 CLI 命令后可复制到终端执行。</p>

        <div class="form-row">
          <div class="form-field">
            <label>设备厂商</label>
            <select v-model="vendor">
              <option value="huawei">华为</option>
              <option value="h3c">华三</option>
              <option value="cisco">思科</option>
              <option value="ruijie">锐捷</option>
              <option value="other">其他/通用</option>
            </select>
          </div>
          <div class="form-field">
            <label>设备类型</label>
            <select v-model="deviceType">
              <option value="router">路由器</option>
              <option value="switch">三层交换机</option>
              <option value="firewall">防火墙</option>
              <option value="other">其他</option>
            </select>
          </div>
        </div>

        <div class="form-field">
          <label>配置需求</label>
          <textarea
            v-model="requirement"
            rows="6"
            placeholder="例如：&#10;- 配置 VLAN 100，名称为 Management&#10;- 接口 G0/0/1 接入 VLAN 100&#10;- 配置 DHCP 地址池 192.168.100.0/24&#10;- 配置 OSPF 区域 0，宣告 10.0.0.0/8 网段"
          />
        </div>

        <div class="feature-tags">
          <span class="label">常用功能（按厂商/类型推荐）：</span>
          <button
            v-for="f in visibleFeatures"
            :key="f"
            type="button"
            class="tag-btn"
            :class="{ active: features.includes(f) }"
            @click="toggleFeature(f)"
          >
            {{ f }}
          </button>
        </div>

        <button class="primary generate-btn" :disabled="!requirement.trim() || generating" @click="generate">
          {{ generating ? '生成中…' : 'AI 生成命令' }}
        </button>
      </section>

      <!-- 左下：生成的 CLI 命令 -->
      <section class="pane pane-bl">
        <h3 class="section-title">生成的 CLI 终端</h3>
        <div class="form-field">
          <label>生成的 CLI 命令（仿设备输出，便于复制）</label>
          <div class="result-area">
            <pre v-if="generated" class="result-text">{{ generated }}</pre>
            <div v-else class="result-placeholder">
              点击左上「AI 生成命令」后，这里会以设备配置风格展示生成的 CLI 命令，可整体复制到右下终端中执行。
            </div>
          </div>
        </div>
        <div class="actions">
          <button type="button" class="secondary" @click="copyToClipboard">复制到剪贴板</button>
        </div>
      </section>

      <!-- 右侧：嵌入终端（直连 SSH WebSocket，独占右列） -->
      <section class="pane pane-br table-loading-wrap">
        <div v-if="devicesLoading" class="table-loading-overlay">
          <div>
            <div class="loading-spinner" aria-hidden="true"></div>
            <p class="loading-text">加载中…</p>
          </div>
        </div>
        <h3 class="section-title">终端</h3>
        <div class="device-select">
          <div class="device-select-header">
            <label>设备分组</label>
            <select v-model="filterGroup" @change="debouncedLoadDevices">
              <option value="">全部分组</option>
              <option v-for="g in groupList" :key="g" :value="g">{{ g }}</option>
            </select>
            <span class="selected-count">已选 {{ selectedDeviceId ? 1 : 0 }}/1</span>
          </div>
          <div class="device-grid-wrap">
            <div v-if="devices.length" class="device-grid">
              <label v-for="d in devices" :key="d.id" class="device-card" :class="{ selected: selectedDeviceId === d.id }">
                <input
                  type="checkbox"
                  class="device-card-check"
                  :checked="selectedDeviceId === d.id"
                  @change="selectSingleDevice(d.id, $event)"
                />
                <div class="device-card-body">
                  <span class="device-card-name" :title="d.name || '未命名设备'">{{ d.name || '未命名设备' }}</span>
                  <span class="device-card-meta">
                    <span class="device-card-ip">{{ d.ip || '-' }}</span>
                    <span class="device-card-type">
                      {{ typeLabel(d.type) }}{{ d.vendor ? ' / ' + d.vendor : '' }}
                    </span>
                  </span>
                </div>
              </label>
            </div>
            <p v-else class="empty-devices">暂无在线网络设备，请先在设备管理中添加并确保设备在线。</p>
          </div>
        </div>
        <p class="hint">
          当前终端：
          <span v-if="currentDevice">
            {{ currentDevice.name || '未命名设备' }}（{{ currentDevice.ip || '无 IP' }}）
          </span>
          <span v-else>未连接，请先在上方选择一台网络设备。</span>
          <button
            v-if="currentDeviceId"
            type="button"
            class="link-btn"
            @click="openTerminal"
          >
            连接终端
          </button>
          <button
            v-if="terminalConnected"
            type="button"
            class="link-btn"
            @click="disconnectTerminal"
          >
            断开
          </button>
        </p>
        <div class="terminal-wrap">
          <div v-if="!currentDeviceId" class="terminal-placeholder">
            请选择上方的一台网络设备，并点击「连接终端」按钮后，这里会打开该设备的 SSH 终端。
          </div>
          <div v-else ref="terminalRef" class="terminal-embed"></div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { getDevices, getDeviceGroups } from '../api/device'
import { aiChat } from '../api/ai'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { debounce } from '../utils/debounce'

const vendor = ref('huawei')
const deviceType = ref('router')
const requirement = ref('')
const allFeatures = ['VLAN', 'ACL', 'OSPF', '静态路由', 'DHCP', 'NAT', 'STP']
const features = ref([])
const visibleFeatures = computed(() => {
  const t = String(deviceType.value || '').trim().toLowerCase()
  if (t === 'switch') return ['VLAN', 'STP', 'ACL', 'DHCP']
  if (t === 'router') return ['OSPF', '静态路由', 'ACL', 'NAT', 'DHCP']
  if (t === 'firewall') return ['ACL', 'NAT', '静态路由', 'DHCP']
  return allFeatures
})

const generated = ref('')
const generating = ref(false)

const devices = ref([])
const devicesLoading = ref(false)
const groupList = ref([])
const filterGroup = ref('')
const selectedDeviceId = ref(null)
const terminalConnected = ref(false)

const currentDeviceId = computed(() =>
  selectedDeviceId.value || null
)

const currentDevice = computed(() =>
  devices.value.find(d => d.id === currentDeviceId.value) || null
)
const effectiveVendor = computed(() => {
  if (terminalConnected.value && currentDevice.value) {
    return normalizeVendor(currentDevice.value.vendor) || vendor.value
  }
  return vendor.value
})
const effectiveDeviceType = computed(() => {
  if (terminalConnected.value && currentDevice.value) {
    return normalizeDeviceType(currentDevice.value.type) || deviceType.value
  }
  return deviceType.value
})

const terminalRef = ref(null)
let terminal = null
let fitAddon = null
let ws = null
let dataListener = null
let pasteContextHandler = null

function toggleFeature(f) {
  const idx = features.value.indexOf(f)
  if (idx >= 0) {
    features.value.splice(idx, 1)
  } else {
    features.value.push(f)
  }
  applyFeatureTemplate()
}

function typeLabel(t) {
  const m = { server: '服务器', switch: '交换机', router: '路由器', firewall: '防火墙', other: '其他' }
  return m[t] || t || '-'
}

function normalizeVendor(v) {
  const s = String(v || '').trim().toLowerCase()
  if (!s) return null
  if (s.includes('huawei') || s.includes('华为')) return 'huawei'
  if (s.includes('h3c') || s.includes('华三')) return 'h3c'
  if (s.includes('cisco') || s.includes('思科')) return 'cisco'
  if (s.includes('ruijie') || s.includes('锐捷')) return 'ruijie'
  return 'other'
}

function normalizeDeviceType(t) {
  const s = String(t || '').trim().toLowerCase()
  if (!s) return null
  if (s === 'switch' || s === 'sw' || s.includes('交换')) return 'switch'
  if (s === 'router' || s.includes('路由')) return 'router'
  if (s === 'firewall' || s.includes('防火墙')) return 'firewall'
  return 'other'
}

/** 按当前终端设备自动同步 AI 生成的厂商与设备类型 */
function syncAiContextByCurrentDevice() {
  const d = currentDevice.value
  if (!d) return
  const v = normalizeVendor(d.vendor)
  const t = normalizeDeviceType(d.type)
  let changed = false
  if (v && vendor.value !== v) {
    vendor.value = v
    changed = true
  }
  if (t && deviceType.value !== t) {
    deviceType.value = t
    changed = true
  }
  if (changed) applyFeatureTemplate()
}

function loadDevices() {
  devicesLoading.value = true
  getDevices(filterGroup.value ? { group: filterGroup.value } : {})
    .then((r) => {
      const all = r.data != null ? r.data : []
      devices.value = all.filter((d) => d.type && d.type !== 'server' && d.status !== 'offline')
      if (!devices.value.some((d) => d.id === selectedDeviceId.value)) {
        selectedDeviceId.value = null
      }
      if (devices.value.length === 1 && !requirement.value.trim() && devices.value[0].vendor) {
        const v = String(devices.value[0].vendor).toLowerCase()
        if (v.includes('huawei')) vendor.value = 'huawei'
        else if (v.includes('cisco')) vendor.value = 'cisco'
        else if (v.includes('h3c')) vendor.value = 'h3c'
        else if (v.includes('ruijie')) vendor.value = 'ruijie'
      }
    })
    .catch(() => {
      devices.value = []
      alert('设备列表加载失败，请检查网络或后端服务')
    })
    .finally(() => { devicesLoading.value = false })
  getDeviceGroups().then((r) => (groupList.value = r.data || [])).catch(() => {})
}
const debouncedLoadDevices = debounce(loadDevices, 300)

function selectSingleDevice(deviceId, event) {
  const checked = event?.target?.checked === true
  if (!checked) {
    selectedDeviceId.value = null
    return
  }
  selectedDeviceId.value = deviceId
  syncAiContextByCurrentDevice()
}

function buildAiPrompt(strictRetry = false, badOutput = '') {
  const vendorText = { huawei: '华为（Huawei）', cisco: '思科（Cisco）', h3c: '华三（H3C）', ruijie: '锐捷（Ruijie）', other: '通用厂商' }[
    effectiveVendor.value
  ]
  const typeText = { router: '路由器', switch: '三层交换机', firewall: '防火墙', other: '其他网络设备' }[effectiveDeviceType.value]
  const featureText = features.value.length ? `涉及功能：${features.value.join('、')}。` : ''
  const vendorGuard = {
    huawei: '厂商限定：仅使用华为VRP风格命令；禁止出现 Cisco IOS/H3C/锐捷 特有命令。',
    cisco: '厂商限定：仅使用 Cisco IOS 风格命令；禁止出现华为/H3C/锐捷 特有命令。',
    h3c: '厂商限定：仅使用 H3C Comware 风格命令；禁止出现华为/Cisco/锐捷 特有命令。',
    ruijie: '厂商限定：仅使用锐捷网络设备 CLI 风格命令；禁止出现华为/H3C/Cisco 特有命令。',
    other: '厂商限定：输出通用CLI步骤，并尽量避免厂商私有命令。'
  }[effectiveVendor.value]
  const retryHint = strictRetry
    ? `\n你上一次输出混入了非目标厂商语法，必须修正。错误示例（不要重复）：\n${badOutput}\n`
    : ''
  const completeness = {
    cisco:
      '【完整性—思科】三层 VLAN 场景必须包含：conf t →（三层交换机）ip routing → 逐个 vlan <id> / name → 逐个 interface Vlan<id> 配 ip address 与 no shutdown → end。禁止只输出 interface Vlan 而无 vlan 创建段。',
    huawei:
      '【完整性—华为】三层 VLAN 场景必须包含：system-view → vlan batch 或逐条 vlan → 逐个 interface Vlanif<id> 配 ip address → quit；接口需 undo shutdown（如适用）。禁止只给 Vlanif 而无 vlan 声明。',
    h3c:
      '【完整性—华三】三层 VLAN 场景必须包含：system-view → 创建 vlan → 逐个 interface Vlan-interface<id> 配 ip address → quit。命令须为 Comware 风格。',
    ruijie:
      '【完整性—锐捷】须使用锐捷 CLI 习惯（与 Cisco 类似时常用 conf t、vlan、interface Vlan），给出从进入配置到各 VLAN 网关就绪的完整步骤，禁止混入华为 Vlanif/vlan batch 语法。',
    other:
      '【完整性】凡涉及多 VLAN/三层网关，须逐步写全：VLAN 创建、三层接口、IP、接口 up，勿只给片段。'
  }[effectiveVendor.value]

  return `你是一名资深网络工程师，请为${vendorText}的${typeText}生成配置命令（CLI），满足以下需求：\n${requirement.value}\n${featureText}\n${vendorGuard}\n${completeness}${retryHint}\n要求：\n1. 只输出可直接在设备上执行的配置命令，不要解释文字。\n2. 按合理顺序排列，必要时分段加空行；涉及多个 VLAN 时每个 VLAN 写完整一段。\n3. 禁止输出「不完整片段」（例如仅有 interface 而无前置 vlan 创建），除非用户明确只要改某一接口。\n4. 如果无法确认某条命令在该厂商下的准确写法，请用以 # 开头的注释占位，不要改用其他厂商语法。`
}

function buildFeatureTemplate(v, t, feature) {
  const vendorKey = v || 'other'
  const typeKey = t || 'other'
  // 统一用中文说明 + 对应厂商 CLI 示例，用户可在此基础上修改
  if (feature === 'VLAN') {
    if (vendorKey === 'huawei') {
      return `需求：在 ${typeKey === 'switch' ? '三层交换机' : '设备'} 上创建 VLAN 100 作为管理 VLAN，并将接口 G0/0/1 加入该 VLAN。\n\n示例 CLI（请根据实际接口/描述修改）：\n# 创建 VLAN 100 并命名\nvlan batch 100\nvlan 100\n description Management\n quit\n\n# 接口加入 VLAN 100（访问口）\ninterface GigabitEthernet0/0/1\n port link-type access\n port default vlan 100\n quit`
    }
    if (vendorKey === 'cisco') {
      return `需求：在 ${typeKey === 'switch' ? '三层交换机' : '设备'} 上创建 VLAN 100 作为管理 VLAN，并将接口 G0/0/1 加入该 VLAN。\n\n示例 CLI（请根据实际接口/描述修改）：\nconf t\n vlan 100\n  name Management\n exit\n\n interface GigabitEthernet0/0/1\n  switchport mode access\n  switchport access vlan 100\n exit\nend`
    }
    return `需求：创建 VLAN 100 作为管理 VLAN，并将一个接入口加入该 VLAN。\n\n请在下方根据你的设备语法填写具体 CLI，例如：\n- 创建 VLAN 100 并命名为 Management\n- 将接口 G0/0/1 加入 VLAN 100（访问口）`
  }
  if (feature === 'ACL') {
    if (vendorKey === 'huawei') {
      return `需求：在设备上创建基础 ACL，允许 192.168.1.0/24，拒绝其它流量，并应用到入方向接口 G0/0/1。\n\n示例 CLI（请根据实际接口/ACL 号修改）：\nacl number 2000\n rule 5 permit source 192.168.1.0 0.0.0.255\n rule 10 deny source any\n quit\n\ninterface GigabitEthernet0/0/1\n traffic-filter inbound acl 2000\n quit`
    }
    if (vendorKey === 'cisco') {
      return `需求：在设备上创建 ACL，允许 192.168.1.0/24，拒绝其它流量，并应用到入方向接口 G0/0/1。\n\n示例 CLI（请根据实际接口/ACL 号修改）：\nconf t\n ip access-list extended NETPULSE-FILTER\n  permit ip 192.168.1.0 0.0.0.255 any\n  deny ip any any\n exit\n\n interface GigabitEthernet0/0/1\n  ip access-group NETPULSE-FILTER in\n exit\nend`
    }
    return `需求：创建 ACL 以允许 192.168.1.0/24，拒绝其它流量，并将其应用到某接口的入方向。请在下方补充具体 CLI。`
  }
  if (feature === 'OSPF') {
    if (vendorKey === 'huawei') {
      return `需求：在 ${typeKey === 'router' ? '路由器' : '设备'} 上启用 OSPF 进程 1，宣告 10.0.0.0/24 和 192.168.1.0/24 到 area 0。\n\n示例 CLI（请根据实际网段/接口修改）：\nospf 1 router-id 1.1.1.1\n area 0.0.0.0\n  network 10.0.0.0 0.0.0.255\n  network 192.168.1.0 0.0.0.255\n quit`
    }
    if (vendorKey === 'cisco') {
      return `需求：在 ${typeKey === 'router' ? '路由器' : '设备'} 上启用 OSPF 进程 1，宣告 10.0.0.0/24 和 192.168.1.0/24 到 area 0。\n\n示例 CLI（请根据实际网段/接口修改）：\nconf t\n router ospf 1\n  router-id 1.1.1.1\n  network 10.0.0.0 0.0.0.255 area 0\n  network 192.168.1.0 0.0.0.255 area 0\n exit\nend`
    }
    return `需求：配置 OSPF 进程 1，将若干网段加入 area 0。请在下方补充具体 CLI，如 router-id、network 语句等。`
  }
  if (feature === '静态路由') {
    if (vendorKey === 'huawei') {
      return `需求：在设备上配置一条静态路由，将 10.10.10.0/24 指向下一跳 192.168.1.254。\n\n示例 CLI：\nip route-static 10.10.10.0 255.255.255.0 192.168.1.254`
    }
    if (vendorKey === 'cisco') {
      return `需求：在设备上配置一条静态路由，将 10.10.10.0/24 指向下一跳 192.168.1.254。\n\n示例 CLI：\nconf t\n ip route 10.10.10.0 255.255.255.0 192.168.1.254\nend`
    }
    return `需求：配置一条静态路由，将某个目标网段指向指定下一跳。请在下方补充具体网段和下一跳 IP 的 CLI。`
  }
  if (feature === 'DHCP') {
    if (vendorKey === 'huawei') {
      return `需求：在设备上为 192.168.100.0/24 网段提供 DHCP 地址分配。\n\n示例 CLI（根据实际接口/网关修改）：\nip pool VLAN100\n gateway-list 192.168.100.1\n network 192.168.100.0 mask 255.255.255.0\n dns-list 8.8.8.8\n quit\n\ninterface Vlanif100\n ip address 192.168.100.1 255.255.255.0\n dhcp select global\n quit`
    }
    if (vendorKey === 'cisco') {
      return `需求：在设备上为 192.168.100.0/24 网段提供 DHCP 地址分配。\n\n示例 CLI：\nconf t\n ip dhcp pool VLAN100\n  network 192.168.100.0 255.255.255.0\n  default-router 192.168.100.1\n  dns-server 8.8.8.8\n exit\n\n interface Vlan100\n  ip address 192.168.100.1 255.255.255.0\n exit\nend`
    }
    return `需求：为某个 VLAN/网段配置 DHCP 地址池。请在下方补充 network、gateway 等具体 CLI。`
  }
  if (feature === 'NAT') {
    if (vendorKey === 'huawei') {
      return `需求：配置源 NAT，使内网 192.168.1.0/24 通过出口接口 GigabitEthernet0/0/0 访问互联网。\n\n示例 CLI（简单模式，需根据实际防火墙/NAT 场景调整）：\nacl number 3000\n rule 5 permit source 192.168.1.0 0.0.0.255\n quit\n\ninterface GigabitEthernet0/0/0\n ip address x.x.x.x 255.255.255.252\n nat outbound 3000\n quit`
    }
    if (vendorKey === 'cisco') {
      return `需求：配置源 NAT，使内网 192.168.1.0/24 通过外网口 G0/0/0 出口访问互联网。\n\n示例 CLI：\nconf t\n access-list 10 permit 192.168.1.0 0.0.0.255\n interface GigabitEthernet0/0/0\n  ip address x.x.x.x 255.255.255.252\n  ip nat outside\n exit\n interface GigabitEthernet0/0/1\n  ip address 192.168.1.1 255.255.255.0\n  ip nat inside\n exit\n ip nat inside source list 10 interface GigabitEthernet0/0/0 overload\nend`
    }
    return `需求：配置源 NAT，让内网网段通过某个出口接口上网。请在下方补充 ACL、inside/outside 或等价 CLI。`
  }
  if (feature === 'STP') {
    if (vendorKey === 'huawei') {
      return `需求：在二层/三层交换设备上启用 MSTP，配置实例 1 映射 VLAN 10-20，并将本设备提升为根桥。\n\n示例 CLI：\nstp mode mstp\nstp region-configuration\n region-name NETPULSE\n instance 1 vlan 10 to 20\n active region-configuration\n quit\nstp instance 1 root primary`
    }
    if (vendorKey === 'cisco') {
      return `需求：在交换机上启用 MST 并将 VLAN 10-20 放入实例 1，将本交换机设置为实例 1 的根桥。\n\n示例 CLI：\nconf t\n spanning-tree mode mst\n spanning-tree mst configuration\n  name NETPULSE\n  instance 1 vlan 10-20\n exit\n spanning-tree mst 1 root primary\nend`
    }
    return `需求：在交换设备上启用生成树（STP/MSTP），并为若干 VLAN 配置生成树实例及根桥。请在下方补充具体 CLI。`
  }
  return ''
}

function getAutoFeatureByDeviceType(type) {
  const t = String(type || '').trim().toLowerCase()
  if (t === 'switch') return 'VLAN'
  if (t === 'router') return 'OSPF'
  if (t === 'firewall') return 'ACL'
  return '静态路由'
}

/** 按当前厂商/设备类型/常用功能自动生成对应模板。 */
function applyFeatureTemplate() {
  // 设备类型切换后，移除不在当前推荐范围内的已选功能
  features.value = features.value.filter(f => visibleFeatures.value.includes(f))
  const feature = features.value.length
    ? features.value[features.value.length - 1]
    : getAutoFeatureByDeviceType(effectiveDeviceType.value)
  const tpl = buildFeatureTemplate(effectiveVendor.value, effectiveDeviceType.value, feature)
  if (tpl) requirement.value = tpl
}

function hasVendorMismatch(output, selectedVendor) {
  const t = String(output || '')
  if (!t.trim()) return false
  if (selectedVendor === 'cisco') {
    // 思科场景下常见的华为/H3C风格关键字
    return /(Vlanif|vlan\s+batch|\bundo\b|traffic-filter|port\s+link-type|port\s+default\s+vlan)/i.test(t)
  }
  if (selectedVendor === 'huawei') {
    // 华为场景下常见的 Cisco 风格关键字
    return /(conf\s+t|configure\s+terminal|switchport|ip\s+access-list|spanning-tree\s+mst)/i.test(t)
  }
  if (selectedVendor === 'ruijie') {
    // 锐捷先按“不得混华为/Cisco 典型命令”兜底
    return /(Vlanif|vlan\s+batch|\bundo\b|conf\s+t|switchport)/i.test(t)
  }
  return false
}

async function generate() {
  const text = requirement.value.trim()
  if (!text || generating.value) return
  generating.value = true
  try {
    // 复用 AI 运维助手的 chat 接口，走一次性对话（不绑定会话）
    const firstPrompt = buildAiPrompt()
    const firstResp = await aiChat(undefined, firstPrompt, { transient: true })
    let reply = firstResp?.data?.reply ?? ''

    // 若检测到厂商语法串台，自动强约束重试一次
    if (hasVendorMismatch(reply, effectiveVendor.value)) {
      const retryPrompt = buildAiPrompt(true, reply)
      const retryResp = await aiChat(undefined, retryPrompt, { transient: true })
      const retryReply = retryResp?.data?.reply ?? ''
      if (retryReply.trim()) reply = retryReply
    }

    generated.value = reply
    if (hasVendorMismatch(reply, effectiveVendor.value)) {
      alert('检测到命令可能混入了其他厂商语法。建议补充更具体需求（接口名、协议、目标网段）后重试。')
    }
  } catch (e) {
    alert(e.response?.data?.message || e.message || 'AI 生成失败，请检查 AI 配置')
  } finally {
    generating.value = false
  }
}

function copyToClipboard() {
  if (!generated.value.trim()) return
  try {
    navigator.clipboard.writeText(generated.value)
    alert('已复制到剪贴板')
  } catch (e) {
    alert('复制失败，请手动选择文本复制')
  }
}

function getWsUrl(deviceId) {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = location.host
  return `${protocol}//${host}/api/ws/ssh?deviceId=${deviceId}`
}

function initTerminal() {
  if (!terminalRef.value || terminal) return
  terminal = new Terminal({
    cursorBlink: true,
    theme: { background: '#020617', foreground: '#e5e7eb', cursor: '#e5e7eb' },
    fontFamily: 'Consolas, "Courier New", monospace',
    fontSize: 14,
  })
  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.open(terminalRef.value)
  fitAddon.fit()
  terminal.attachCustomKeyEventHandler((ev) => {
    const key = String(ev.key || '').toLowerCase()
    const ctrlOrMeta = ev.ctrlKey || ev.metaKey
    if (!ctrlOrMeta) return true
    if (key === 'c') {
      const selected = terminal?.getSelection?.() || ''
      if (selected) {
        ev.preventDefault()
        navigator.clipboard?.writeText?.(selected).catch(() => {})
        terminal?.clearSelection?.()
        return false
      }
      return true
    }
    if (key === 'v') {
      ev.preventDefault()
      navigator.clipboard?.readText?.().then((text) => {
        if (!text) return
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(text)
      }).catch(() => {})
      return false
    }
    return true
  })
  if (!pasteContextHandler) {
    pasteContextHandler = (e) => {
      e.preventDefault()
      navigator.clipboard?.readText?.().then((text) => {
        if (!text) return
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(text)
      }).catch(() => {})
    }
  }
  terminalRef.value.addEventListener('contextmenu', pasteContextHandler)
}

function disconnectTerminal() {
  if (ws) {
    ws.close()
    ws = null
  }
  terminalConnected.value = false
}

function connectTerminal(deviceId) {
  if (!deviceId) return
  disconnectTerminal()
  initTerminal()
  const url = getWsUrl(deviceId)
  ws = new WebSocket(url)
  ws.onopen = () => {
    terminalConnected.value = true
    terminal?.writeln('\r\n已连接到设备 ' + deviceId + '，可粘贴命令执行。\r\n')
  }
  ws.onmessage = (ev) => {
    const data = typeof ev.data === 'string' ? ev.data : ''
    try {
      const j = JSON.parse(data)
      if (j.error) {
        terminal?.writeln('\r\n[错误] ' + j.error + '\r\n')
        return
      }
    } catch (_) {}
    terminal?.write(data)
  }
  ws.onerror = () => {
    terminal?.writeln('\r\n[错误] WebSocket 连接失败，请检查后端与 SSH 配置。\r\n')
  }
  ws.onclose = (ev) => {
    const hint =
      ev.code === 1006
        ? '（异常断开，多为代理/后端未转发 WebSocket 或 SSH 通道被设备关闭）'
        : ev.code === 1000
          ? ''
          : `（code=${ev.code}${ev.reason ? ' ' + ev.reason : ''}）`
    terminal?.writeln('\r\n连接已关闭。' + hint + '\r\n')
    terminalConnected.value = false
    ws = null
  }
  if (dataListener) {
    dataListener.dispose()
    dataListener = null
  }
  dataListener = terminal?.onData((data) => {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(data)
  })
}

function handleResize() {
  fitAddon?.fit()
}

function openTerminal() {
  if (!currentDeviceId.value) {
    alert('请先在右上选择一台网络设备')
    return
  }
  syncAiContextByCurrentDevice()
  connectTerminal(currentDeviceId.value)
}

onMounted(() => {
  loadDevices()
  applyFeatureTemplate()
  nextTick(() => {
    window.addEventListener('resize', handleResize)
  })
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  disconnectTerminal()
  if (dataListener) {
    dataListener.dispose()
    dataListener = null
  }
  if (terminalRef.value && pasteContextHandler) {
    terminalRef.value.removeEventListener('contextmenu', pasteContextHandler)
  }
  pasteContextHandler = null
  terminal?.dispose()
})

watch(currentDeviceId, (id, prev) => {
  if (!id) {
    disconnectTerminal()
    return
  }
  syncAiContextByCurrentDevice()
  // 换设备时先关旧连接，避免多路 SSH 占满 VTY 或设备主动断开
  if (prev != null && id !== prev) {
    disconnectTerminal()
  }
})

watch([vendor, deviceType], () => {
  applyFeatureTemplate()
})

watch([terminalConnected, currentDeviceId], () => {
  applyFeatureTemplate()
})
</script>

<style scoped>
.network-ai-page {
  padding: 0;
}
.layout-4 {
  display: grid;
  grid-template-columns: 1fr 1.3fr;
  grid-template-rows: auto auto;
  gap: 1.25rem;
}
.pane {
  border-radius: 10px;
  border: 1px solid #e5e7eb;
  padding: 0.75rem 0.9rem 0.9rem;
  background: #fff;
  display: flex;
  flex-direction: column;
}
.pane-tl {
  min-height: 220px;
}
.pane-bl,
.pane-br {
  min-height: 260px;
}
.pane-tl {
  grid-column: 1;
  grid-row: 1;
}
.pane-bl {
  grid-column: 1;
  grid-row: 2;
}
.pane-br {
  grid-column: 2;
  grid-row: 1 / span 2;
}
.section-title {
  margin: 0 0 0.5rem;
  font-size: 1rem;
  font-weight: 600;
  color: #0f172a;
}
.hint {
  margin: 0 0 0.75rem;
  font-size: 0.85rem;
  color: #6b7280;
}
.form-row {
  display: flex;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}
.form-field {
  margin-bottom: 0.75rem;
  display: flex;
  flex-direction: column;
}
.form-field label {
  font-size: 0.85rem;
  color: #4b5563;
  margin-bottom: 0.25rem;
}
select,
textarea {
  font-size: 0.875rem;
  padding: 0.45rem 0.5rem;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
  resize: vertical;
}
.feature-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin-bottom: 0.75rem;
}
.feature-tags .label {
  font-size: 0.85rem;
  color: #4b5563;
}
.tag-btn {
  padding: 0.15rem 0.6rem;
  font-size: 0.8rem;
  border-radius: 999px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  cursor: pointer;
}
.tag-btn.active {
  background: #dbeafe;
  border-color: #60a5fa;
  color: #1d4ed8;
}
.primary {
  background: #2563eb;
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 0.45rem 0.9rem;
  font-size: 0.875rem;
  cursor: pointer;
}
.primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.secondary {
  background: #f3f4f6;
  color: #374151;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0.4rem 0.8rem;
  font-size: 0.85rem;
  cursor: pointer;
}
.generate-btn {
  margin-top: 0.25rem;
}
.right .actions {
  display: flex;
  gap: 0.6rem;
  margin-bottom: 0.75rem;
}
.result-area {
  min-height: 260px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  background: #ffffff;
  color: #111827;
  border-radius: 8px;
  border: 1px solid #d4d4d8;
  padding: 0.75rem 0.9rem;
  line-height: 1.5;
  overflow: auto;
  box-shadow: 0 0 0 1px #e5e7eb inset;
}
.result-text {
  white-space: pre;
}
.result-placeholder {
  font-size: 0.85rem;
  color: #9ca3af;
}
.device-select {
  margin-top: 0.25rem;
}
.device-select-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}
.device-select-header label {
  margin: 0;
}
.device-select-header select {
  max-width: 140px;
}
.check-all {
  font-size: 0.8rem;
  color: #4b5563;
}
.selected-count {
  font-size: 0.8rem;
  color: #6b7280;
}
.device-grid-wrap {
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fafbfc;
  padding: 1rem;
  max-height: 260px;
  overflow-y: auto;
}
.device-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 0.75rem;
}
.device-card {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  padding: 0.65rem 0.85rem;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
  min-height: 52px;
  box-sizing: border-box;
}
.device-card:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
}
.device-card.selected {
  border-color: #2563eb;
  background: #eff6ff;
}
.device-card-check {
  flex-shrink: 0;
  margin: 0.25rem 0 0 0;
}
.device-card-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.device-card-name {
  font-size: 0.875rem;
  font-weight: 500;
  color: #111827;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.device-card-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.device-card-ip {
  font-size: 0.8125rem;
  font-family: ui-monospace, monospace;
  color: #64748b;
}
.device-card-type {
  font-size: 0.75rem;
  color: #6b7280;
  padding: 0.12rem 0.4rem;
  background: #f1f5f9;
  border-radius: 4px;
}
.empty-devices {
  margin: 1rem;
  color: #94a3b8;
  font-size: 0.8125rem;
  text-align: center;
}
.terminal-wrap {
  /* 终端区域统一深色显示，避免出现白底 */
  flex: 0 0 auto;
  width: 100%;
  max-width: none;
  margin: 0;
  min-height: 560px;
  border-radius: 8px;
  border: 1px solid #1f2937;
  background: #020617;
  overflow: auto;
  display: flex;
  align-items: stretch;
  justify-content: center;
  scrollbar-width: thin;
  scrollbar-color: #475569 #0b1220;
}
.terminal-embed {
  flex: 1;
  min-height: 560px;
  background: #020617;
}
.terminal-wrap::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}
.terminal-wrap::-webkit-scrollbar-track {
  background: #0b1220;
}
.terminal-wrap::-webkit-scrollbar-thumb {
  background: #475569;
  border-radius: 999px;
}
.terminal-wrap::-webkit-scrollbar-thumb:hover {
  background: #64748b;
}
.terminal-placeholder {
  width: 100%;
  min-height: 560px;
  background: #020617;
  color: #9ca3af;
  font-size: 0.85rem;
  padding: 1rem;
  box-sizing: border-box;
}
.link-btn {
  margin-left: 0.5rem;
  font-size: 0.8rem;
  padding: 0.2rem 0.6rem;
  border-radius: 999px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  color: #2563eb;
  cursor: pointer;
}
.link-btn:hover {
  background: #eff6ff;
  border-color: #bfdbfe;
}
@media (max-width: 1080px) {
  .layout-4 {
    grid-template-columns: 1fr;
    grid-template-rows: auto;
  }
  .pane-tl,
  .pane-bl,
  .pane-br {
    grid-column: auto;
    grid-row: auto;
  }
  .terminal-embed {
    min-height: 320px;
  }
  .terminal-wrap {
    max-width: none;
  }
}
</style>

