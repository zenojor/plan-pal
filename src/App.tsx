import { useEffect, useMemo, useRef, useState } from 'react'
import type { DragEvent, FormEvent } from 'react'
import { Button, Card, Input } from 'animal-island-ui'
import './index.css'

type Stage = 'intro' | 'planning' | 'confirmed'
type ColumnId = 'puzzle' | 'merchant' | 'details' | 'map'

type PlanNode = {
  id: string
  time: string
  title: string
  place: string
  lnglat: [number, number]
  audience: string
  reason: string
  budget: string
  status: string
  details: string
}

const columnMeta: Record<ColumnId, { title: string; hint: string }> = {
  puzzle: { title: '拼图', hint: '按时间顺序排列的活动节点' },
  merchant: { title: '商家', hint: '当前选中地点的商家信息' },
  details: { title: '地点详情', hint: '商家、排队、预约与适配提示' },
  map: { title: '路线地图', hint: '自动标记本次计划路线' },
}

type MerchantProfile = {
  address: string
  queue: string
  booking: string
  hours: string
  contact: string
  tags: string[]
}

const examplePrompts = [
  '周六下午带 5 岁孩子和朋友在本地玩 4 小时，别太远，要好吃好走',
  '一家三口周末下午放松一下，最好能吃饭、散步、给孩子放电',
  '四个朋友临时约出门，想要吃饭加轻活动，不想排太久队',
]

const basePlan: PlanNode[] = [
  {
    id: 'start',
    time: '14:00',
    title: '集合出发',
    place: '家附近地铁站',
    lnglat: [121.4737, 31.2304],
    audience: '家庭 / 朋友都适合',
    reason: '先把集合点定在熟悉的位置，减少临时找人的成本。',
    budget: '交通约 ¥20-40',
    status: '轻松开场',
    details: '提前 10 分钟发定位，确认孩子水杯、纸巾和充电宝。',
  },
  {
    id: 'play',
    time: '14:40',
    title: '亲子探索馆',
    place: '鹿屿自然探索馆',
    lnglat: [121.4891, 31.2250],
    audience: '5 岁孩子友好',
    reason: '室内外结合，孩子能跑动，成年人也可以边走边聊。',
    budget: '门票约 ¥68/人',
    status: '核心活动',
    details: '周末建议线上买票，15:00 前入场通常不用等太久。',
  },
  {
    id: 'snack',
    time: '16:15',
    title: '甜点补给',
    place: '栗子坡面包屋',
    lnglat: [121.4952, 31.2218],
    audience: '孩子 / 朋友都能接受',
    reason: '离探索馆步行 8 分钟，适合补充体力，也能避开正餐排队。',
    budget: '人均 ¥25-45',
    status: '缓冲节点',
    details: '有儿童椅和低糖饮品，建议点可分食的吐司盒。',
  },
  {
    id: 'walk',
    time: '17:00',
    title: 'Citywalk 小街区',
    place: '青禾慢行街',
    lnglat: [121.5010, 31.2185],
    audience: '拍照 / 散步 /遛娃',
    reason: '路程短、车少、店铺密集，适合根据大家体力灵活缩短。',
    budget: '可免费',
    status: '可伸缩',
    details: '路线约 900 米，沿途有洗手间和便利店。',
  },
  {
    id: 'dinner',
    time: '18:00',
    title: '早晚饭收尾',
    place: '花椒鱼与蒸菜小馆',
    lnglat: [121.5068, 31.2152],
    audience: '大人小孩都能点',
    reason: '有包间和清淡菜，能照顾孩子，也适合朋友聊天。',
    budget: '人均 ¥80-120',
    status: '需要预约',
    details: '建议 17:30 前电话留位，点一个不辣蒸鱼和两个小菜。',
  },
]

const alternatives: Record<string, PlanNode[]> = {
  play: [
    {
      id: 'play',
      time: '14:40',
      title: '木作手工坊',
      place: '小树枝创作屋',
      lnglat: [121.4860, 31.2270],
      audience: '孩子专注 / 大人省心',
      reason: '比大型场馆更安静，适合怕累或天气不稳定的下午。',
      budget: '体验约 ¥98/组',
      status: '可预约',
      details: '需要提前 30 分钟预约，作品可当天带走。',
    },
    {
      id: 'play',
      time: '14:40',
      title: '儿童友好书店',
      place: '云朵绘本馆',
      lnglat: [121.4825, 31.2290],
      audience: '低体力方案',
      reason: '更安静，适合家长聊天，孩子能读书和参加手作角。',
      budget: '饮品人均 ¥30-50',
      status: '雨天备选',
      details: '周末 15:30 有绘本故事会，座位有限。',
    },
  ],
  snack: [
    {
      id: 'snack',
      time: '16:15',
      title: '热汤小食',
      place: '巷口馄饨铺',
      lnglat: [121.4940, 31.2225],
      audience: '老人孩子也稳',
      reason: '如果天气偏冷，用一顿轻食恢复体力，比甜点更顶饱。',
      budget: '人均 ¥22-35',
      status: '少排队',
      details: '翻台快，可扫码点单，儿童餐具需要向店员拿。',
    },
  ],
  walk: [
    {
      id: 'walk',
      time: '17:00',
      title: '小公园放电',
      place: '月芽社区公园',
      lnglat: [121.4985, 31.2195],
      audience: '孩子优先',
      reason: '比街区更适合孩子跑动，大人可以在长椅休息。',
      budget: '免费',
      status: '户外活动',
      details: '有滑梯和沙坑，建议带湿巾。',
    },
  ],
  dinner: [
    {
      id: 'dinner',
      time: '18:00',
      title: '轻松披萨晚饭',
      place: '森谷窑烤披萨',
      lnglat: [121.5045, 31.2160],
      audience: '朋友聚会友好',
      reason: '上菜快、选择简单，大家口味分歧小。',
      budget: '人均 ¥70-100',
      status: '好分食',
      details: '可提前排号，儿童座椅较少。',
    },
  ],
}


const scheduleSlots = ['14:00', '14:40', '16:15', '17:00', '18:00']

const merchantProfiles: Record<string, MerchantProfile> = {
  鹿屿自然探索馆: {
    address: '青禾路 88 号自然广场 2F',
    queue: '周末 14:30-16:00 人流较高，线上购票可快速入场。',
    booking: '建议提前 1 小时预约亲子场次。',
    hours: '10:00-19:30',
    contact: '021-8848 1024',
    tags: ['儿童友好', '室内外结合', '可寄存'],
  },
  栗子坡面包屋: {
    address: '慢行街 12 号转角铺',
    queue: '下午茶高峰约等 10-15 分钟，可外带。',
    booking: '无需预约，6 人以上可电话留座。',
    hours: '08:30-20:30',
    contact: '021-7726 3318',
    tags: ['儿童椅', '低糖饮品', '步行可达'],
  },
  青禾慢行街: {
    address: '青禾路至月芽桥步行区',
    queue: '开放街区，不需要排队。',
    booking: '无需预约，雨天建议改室内备选。',
    hours: '全天开放',
    contact: '公共街区',
    tags: ['可散步', '车少', '洗手间方便'],
  },
  花椒鱼与蒸菜小馆: {
    address: '花溪巷 6 号 1F',
    queue: '18:00 后通常等位 20 分钟左右。',
    booking: '建议 17:30 前电话留位，儿童椅数量有限。',
    hours: '11:00-21:30',
    contact: '021-6639 8821',
    tags: ['可包间', '清淡菜', '适合收尾'],
  },
  小树枝创作屋: {
    address: '云杉里 3 号楼 105',
    queue: '按场次进入，现场等位不稳定。',
    booking: '需要提前 30 分钟预约。',
    hours: '10:30-18:30',
    contact: '021-7712 9055',
    tags: ['手作体验', '安静', '作品可带走'],
  },
  云朵绘本馆: {
    address: '青禾书街 19 号',
    queue: '故事会前后座位紧张。',
    booking: '15:30 故事会建议提前报名。',
    hours: '10:00-20:00',
    contact: '021-5508 7620',
    tags: ['低体力', '绘本', '雨天备选'],
  },
  巷口馄饨铺: {
    address: '慢行街北口 4 号',
    queue: '翻台快，通常 5-8 分钟。',
    booking: '无需预约，扫码点单。',
    hours: '07:00-22:00',
    contact: '021-6631 1190',
    tags: ['热食', '少排队', '老人孩子稳'],
  },
  月芽社区公园: {
    address: '月芽路与南桥路交叉口',
    queue: '开放公园，不需要排队。',
    booking: '无需预约，沙坑区雨后可能关闭。',
    hours: '06:00-22:00',
    contact: '公共设施',
    tags: ['免费', '放电', '户外'],
  },
  森谷窑烤披萨: {
    address: '花溪巷 18 号',
    queue: '晚餐高峰约等 15-25 分钟。',
    booking: '可提前排号，儿童座椅较少。',
    hours: '11:30-22:00',
    contact: '021-8810 7732',
    tags: ['好分食', '上菜快', '朋友聚会'],
  },
}

function App() {
  const [stage, setStage] = useState<Stage>('intro')
  const [requirement, setRequirement] = useState('')
  const [draft, setDraft] = useState('')
  const [planNodes, setPlanNodes] = useState<PlanNode[]>(basePlan)
  const [columns, setColumns] = useState<ColumnId[]>(['puzzle'])
  const [draggingColumn, setDraggingColumn] = useState<ColumnId | null>(null)
  const [draggingNodeId, setDraggingNodeId] = useState<string | null>(null)
  const [dragOverColumn, setDragOverColumn] = useState<ColumnId | null>(null)
  const [dragOverNodeId, setDragOverNodeId] = useState<string | null>(null)
  const [isColumnMenuOpen, setIsColumnMenuOpen] = useState(false)
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null)
  const [selectedMerchantPlace, setSelectedMerchantPlace] = useState<string | null>(null)
  const [nodeDraft, setNodeDraft] = useState('')
  const [activeMobileTab, setActiveMobileTab] = useState<ColumnId>('puzzle')
  const columnContainerRef = useRef<HTMLDivElement>(null)

  const closedColumns = useMemo(
    () =>
      (Object.keys(columnMeta) as ColumnId[]).filter(
        (column) => column !== 'puzzle' && !columns.includes(column),
      ),
    [columns],
  )
  const columnOptions = useMemo(
    () =>
      closedColumns.map((column) => ({
        key: column,
        label: columnMeta[column].title,
      })),
    [closedColumns],
  )
  const scheduledNodes = useMemo(
    () =>
      planNodes.map((node, index) => ({
        ...node,
        time: scheduleSlots[index] ?? node.time,
      })),
    [planNodes],
  )

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (
        columnContainerRef.current &&
        !columnContainerRef.current.contains(event.target as Node)
      ) {
        setIsColumnMenuOpen(false)
      }
    }
    if (isColumnMenuOpen) {
      document.addEventListener('mousedown', handleClickOutside)
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [isColumnMenuOpen])

  function submitRequirement(event?: FormEvent) {
    event?.preventDefault()
    const text = draft.trim()
    if (!text) return
    setRequirement(text)
    setPlanNodes(basePlan)
    setColumns(['puzzle'])
    setSelectedMerchantPlace(null)
    setActiveMobileTab('puzzle')
    setStage('planning')
  }

  function replaceNode(nodeId: string) {
    const options = alternatives[nodeId]
    if (!options?.length) return
    setPlanNodes((nodes) =>
      nodes.map((node) => {
        if (node.id !== nodeId) return node
        const currentIndex = options.findIndex((item) => item.title === node.title)
        return options[(currentIndex + 1) % options.length]
      }),
    )
  }

  function applyNodeRewrite(nodeId: string) {
    const text = nodeDraft.trim()
    if (!text) return
    setPlanNodes((nodes) =>
      nodes.map((node) =>
        node.id === nodeId
          ? {
              ...node,
              title: text.length > 14 ? `${text.slice(0, 14)}...` : text,
              reason: `已按“${text}”调整这一块，保留原时间顺序和整体节奏。`,
              status: '已按描述改',
            }
          : node,
      ),
    )
    setEditingNodeId(null)
    setNodeDraft('')
  }

  function addColumn(columnId: ColumnId) {
    setColumns((current) => (current.includes(columnId) ? current : [...current, columnId]))
    setIsColumnMenuOpen(false)
  }

  function openMerchantColumn(place: string) {
    setSelectedMerchantPlace(place)
    setColumns((current) => {
      if (current.includes('merchant')) return current
      const puzzleIndex = current.indexOf('puzzle')
      const next = [...current]
      next.splice(puzzleIndex + 1, 0, 'merchant')
      return next
    })
    setIsColumnMenuOpen(false)
    setActiveMobileTab('merchant')
  }

  function removeColumn(columnId: ColumnId) {
    if (columnId === 'puzzle') return
    setColumns((current) => current.filter((column) => column !== columnId))
  }

  function handleColumnDrop(targetColumn: ColumnId) {
    if (!draggingColumn || draggingColumn === targetColumn) return
    setColumns((current) => {
      const fromIndex = current.indexOf(draggingColumn)
      const toIndex = current.indexOf(targetColumn)
      if (fromIndex < 0 || toIndex < 0) return current
      const next = [...current]
      const [moved] = next.splice(fromIndex, 1)
      next.splice(toIndex, 0, moved)
      return next
    })
    setDraggingColumn(null)
  }

  function handleNodeDrop(targetNodeId: string) {
    if (!draggingNodeId || draggingNodeId === targetNodeId) return
    setPlanNodes((nodes) => {
      const fromIndex = nodes.findIndex((node) => node.id === draggingNodeId)
      if (fromIndex < 0) return nodes
      const next = [...nodes]
      const [movedNode] = next.splice(fromIndex, 1)
      if (targetNodeId === '__end__') {
        next.push(movedNode)
      } else {
        const toIndex = nodes.findIndex((node) => node.id === targetNodeId)
        if (toIndex < 0) {
          next.push(movedNode)
        } else {
          next.splice(toIndex, 0, movedNode)
        }
      }
      return next
    })
    setDraggingNodeId(null)
  }

  function moveNodeUp(nodeId: string) {
    setPlanNodes((nodes) => {
      const index = nodes.findIndex((node) => node.id === nodeId)
      if (index <= 0) return nodes
      const next = [...nodes]
      const [moved] = next.splice(index, 1)
      next.splice(index - 1, 0, moved)
      return next
    })
  }

  function moveNodeDown(nodeId: string) {
    setPlanNodes((nodes) => {
      const index = nodes.findIndex((node) => node.id === nodeId)
      if (index < 0 || index >= nodes.length - 1) return nodes
      const next = [...nodes]
      const [moved] = next.splice(index, 1)
      next.splice(index + 1, 0, moved)
      return next
    })
  }

  function allowDrop(event: DragEvent<HTMLElement>) {
    event.preventDefault()
  }

  const boardColsClass = {
    1: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[minmax(320px,620px)] md:justify-center',
    2: 'md:w-[min(1120px,calc(100%-108px))] md:grid-cols-[repeat(2,minmax(0,1fr))] md:justify-center',
    3: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[repeat(3,minmax(0,1fr))]',
    4: 'md:w-[min(1680px,calc(100%-108px))] md:grid-cols-[repeat(4,minmax(0,1fr))]',
  }[columns.length]

  if (stage === 'intro') {
    return (
      <main className="relative grid place-items-center min-h-screen p-8 max-[640px]:p-[18px] bg-animal-grid bg-animal-bg overflow-hidden before:content-[''] before:absolute before:inset-x-[-8%] before:-bottom-[18%] before:h-[36vh] before:bg-[#e6f9f6]/75 before:rounded-t-[50%] before:pointer-events-none">
        <div className="absolute top-[18px] left-1/2 z-10 flex items-center gap-2 px-2.5 py-[7px] border-2 border-animal-border/45 rounded-full bg-[#fff9e8]/86 text-[#794f27] font-black -translate-x-1/2">
          <span>Plan Pal</span>
          <button
            type="button"
            className="border-0 border-l-2 border-animal-border/45 pl-2 bg-transparent text-[#9f927d] cursor-pointer"
          >
            Demo
          </button>
        </div>

        <section className="relative z-10 grid justify-items-center w-full max-w-[820px] mt-[6vh]">
          <div className="grid place-items-center w-[42px] h-[42px] mb-4 rounded-full bg-[#f7cd67] text-[#794f27] text-2xl shadow-[0_5px_0_#dba90e]" aria-hidden="true">
            ✳
          </div>
          <h1 className="mt-0 mb-[30px] text-center text-[#794f27] font-black text-[34px] max-[640px]:text-[34px] min-[641px]:text-[58px] leading-[1.05]">今天想把什么安排好？</h1>

          <form
            className="w-full max-w-[760px] p-5 max-[640px]:p-4 border-2 border-animal-border rounded-[28px] max-[640px]:rounded-[24px] bg-[#f7f3df] shadow-[0_5px_0_0_#d4c9b4,0_16px_48px_rgba(61,52,40,0.12)]"
            onSubmit={submitRequirement}
          >
            <textarea
              className="block w-full min-h-[96px] resize-none border-0 outline-none bg-transparent text-[#725d42] text-lg font-bold placeholder-[#9f927d]"
              value={draft}
              placeholder="告诉我时间、人数、地点偏好和限制..."
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  submitRequirement(event)
                }
              }}
            />
            <div className="flex items-center justify-between gap-3">
              <button
                type="button"
                className="grid place-items-center w-9 h-9 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] text-2xl leading-none cursor-pointer"
                aria-label="添加附件"
              >
                +
              </button>
              <div className="flex items-center gap-3">
                <span className="text-[#9f927d] text-[13px] font-black">本地规划</span>
                <Button
                  htmlType="submit"
                  type="primary"
                  size="small"
                  disabled={!draft.trim()}
                >
                  发送
                </Button>
              </div>
            </div>
          </form>

          <div className="flex flex-wrap justify-center gap-2.5 mt-[18px]" aria-label="快捷需求">
            {examplePrompts.map((prompt) => (
              <button
                type="button"
                key={prompt}
                className="max-w-[250px] max-[640px]:max-w-full px-3 py-2 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] text-[13px] font-extrabold whitespace-nowrap overflow-hidden text-ellipsis cursor-pointer transition-all duration-200 hover:-translate-y-[1px] hover:border-[#a89878]"
                onClick={() => setDraft(prompt)}
              >
                {prompt}
              </button>
            ))}
          </div>
        </section>
      </main>
    )
  }

  return (
    <main className="flex flex-col h-screen min-h-0 bg-animal-grid bg-animal-bg overflow-hidden">
      <header className="shrink-0 relative z-20 flex items-center justify-between gap-3.5 px-4 md:px-[40px] py-3 md:py-3.5 bg-animal-bg/90 border-b border-animal-border-light md:border-b-2 backdrop-blur-md">
        <div className="min-w-0 flex-1 mr-2">
          <strong className="block text-[#794f27] text-[19px] md:text-[21px] font-black">为你推荐</strong>
          <span className="block max-w-[720px] mt-0.5 text-[#725d42] text-[12px] md:text-[13px] font-bold overflow-hidden text-ellipsis whitespace-nowrap">{requirement}</span>
        </div>
        <div className="hidden md:flex items-center gap-2">
          <Button type="default" size="small" onClick={() => setStage('intro')}>
            重新输入
          </Button>
        </div>
        <div className="flex md:hidden items-center gap-2.5 shrink-0">
          <button
            type="button"
            onClick={() => setStage('intro')}
            className="flex items-center justify-center w-9 h-9 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] cursor-pointer shadow-[0_3px_0_0_#d4c9b4] active:translate-y-0.5 active:shadow-[0_1px_0_0_#d4c9b4] transition-all"
            title="重新输入"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
              <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
              <path d="M3 3v5h5" />
            </svg>
          </button>
          <button
            type="button"
            onClick={() => setStage('confirmed')}
            className={`flex items-center justify-center w-9 h-9 border-2 rounded-full cursor-pointer transition-all ${
              stage === 'confirmed'
                ? 'bg-[#4ca61c] border-[#3c8715] text-white shadow-[0_3px_0_0_#2b610f] active:translate-y-0.5 active:shadow-[0_1px_0_0_#2b610f]'
                : 'bg-[#6fba2c] border-[#5a9e1e] text-white shadow-[0_3px_0_0_#437916] active:translate-y-0.5 active:shadow-[0_1px_0_0_#437916]'
            }`}
            title={stage === 'confirmed' ? '已确定' : '确定方案'}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </button>
        </div>
      </header>

      <section className={`grid grid-cols-1 items-stretch flex-1 min-h-0 gap-0 md:gap-3.5 mx-auto w-full px-0 pt-0 pb-0 md:px-3.5 md:pt-3.5 md:pb-[76px] overflow-y-hidden overflow-x-hidden md:overflow-x-auto ${boardColsClass}`}>
        {(() => {
          const columnsToRender = [...columns]
          if (!columnsToRender.includes(activeMobileTab)) {
            columnsToRender.push(activeMobileTab)
          }
          return columnsToRender.map((column) => {
            const isDesktopActive = columns.includes(column)
            const isMobileActive = activeMobileTab === column

            if (!isDesktopActive && !isMobileActive) return null

            let visibilityClass = 'hidden'
            if (isDesktopActive && isMobileActive) {
              visibilityClass = 'flex'
            } else if (isDesktopActive) {
              visibilityClass = 'hidden md:flex'
            } else if (isMobileActive) {
              visibilityClass = 'flex md:hidden'
            }

            return (
              <section
                className={`${visibilityClass} flex-col min-w-0 min-h-0 h-full animate-column-pop transition-all duration-200 ${
                  draggingColumn === column ? 'opacity-50 scale-[0.98] -translate-y-0.5' : ''
                } ${
                  dragOverColumn === column && draggingColumn !== column
                    ? 'ring-4 ring-dashed ring-[#19c8b9] bg-[#e6f9f6]/30 rounded-[26px]'
                    : 'ring-4 ring-transparent'
                }`}
                key={column}
                onDragOver={(event) => {
                  if (draggingColumn) {
                    allowDrop(event)
                  }
                }}
                onDragEnter={() => {
                  if (draggingColumn && draggingColumn !== column) {
                    setDragOverColumn(column)
                  }
                }}
                onDragLeave={(event) => {
                  if (event.currentTarget.contains(event.relatedTarget as Node)) return
                  setDragOverColumn(null)
                }}
                onDrop={() => {
                  if (draggingColumn) {
                    handleColumnDrop(column)
                    setDragOverColumn(null)
                  }
                }}
              >
                <ColumnHeader
                  column={column}
                  onDragEnd={() => {
                    setDraggingColumn(null)
                    setDragOverColumn(null)
                  }}
                  onDragStart={() => setDraggingColumn(column)}
                  onRemove={removeColumn}
                />
                <div className="flex flex-col flex-1 min-h-0 border-0 md:border-2 border-[rgba(196,184,158,0.78)] rounded-none md:rounded-[24px] bg-[#f7f3df] overflow-hidden shadow-none md:shadow-[0_4px_0_0_#d4c9b4,0_12px_28px_rgba(61,52,40,0.09)]">
                  {column === 'puzzle' && (
                    <PuzzleColumn
                      draggingNodeId={draggingNodeId}
                      dragOverNodeId={dragOverNodeId}
                      setDragOverNodeId={setDragOverNodeId}
                      editingNodeId={editingNodeId}
                      nodeDraft={nodeDraft}
                      nodes={scheduledNodes}
                      onApplyRewrite={applyNodeRewrite}
                      onDragEnd={() => {
                        setDraggingNodeId(null)
                        setDragOverNodeId(null)
                      }}
                      onDragStart={setDraggingNodeId}
                      onDrop={handleNodeDrop}
                      onEdit={(nodeId) => {
                        setEditingNodeId(nodeId)
                        setNodeDraft('')
                      }}
                      onOpenMerchant={openMerchantColumn}
                      onReplace={replaceNode}
                      onSetNodeDraft={setNodeDraft}
                      onMoveUp={moveNodeUp}
                      onMoveDown={moveNodeDown}
                    />
                  )}
                  {column === 'merchant' && (
                    <MerchantColumn
                      nodes={scheduledNodes}
                      selectedPlace={selectedMerchantPlace}
                      onSelectPlace={setSelectedMerchantPlace}
                    />
                  )}
                  {column === 'details' && <DetailsColumn nodes={scheduledNodes} />}
                  {column === 'map' && <MapColumn nodes={scheduledNodes} />}
                </div>
              </section>
            )
          })
        })()}
      </section>

      {closedColumns.length > 0 && (
        <div className="fixed top-1/2 right-[clamp(18px,4vw,42px)] z-[35] hidden md:block -translate-y-1/2" ref={columnContainerRef}>
          {isColumnMenuOpen && (
            <div className="absolute top-1/2 right-[58px] flex flex-col gap-1 min-w-[124px] -translate-y-1/2 bg-[#ffeea0] border-2 border-animal-border rounded-[22px] shadow-[0_8px_20px_rgba(61,52,40,0.12)] p-2.5 animate-column-menu-pop">
              {columnOptions.map((option) => (
                <button
                  type="button"
                  key={option.key}
                  className="flex items-center justify-center w-full min-h-[38px] px-4 py-1.5 text-animal-text-body font-black text-[15px] rounded-[14px] cursor-pointer transition-all hover:bg-[#fff9e8]/80 text-center whitespace-nowrap"
                  onClick={() => addColumn(option.key as ColumnId)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          )}
          <button
            className="relative grid place-items-center w-[42px] h-[42px] border-2 border-animal-border rounded-[14px] bg-[#fff9e8] text-[#725d42] cursor-pointer shadow-[0_5px_0_0_#d4c9b4,0_12px_28px_rgba(61,52,40,0.16)] transition-all duration-200 hover:-translate-y-[1px] hover:shadow-[0_6px_0_0_#d4c9b4,0_14px_32px_rgba(61,52,40,0.18)] active:translate-y-0.5 active:shadow-[0_2px_0_0_#d4c9b4]"
            type="button"
            aria-label="添加列"
            aria-expanded={isColumnMenuOpen}
            onClick={() => setIsColumnMenuOpen((open) => !open)}
          >
            <svg
              className="w-[31px] h-[31px] text-[#725d42] overflow-visible"
              viewBox="0 0 40 40"
              role="img"
              aria-hidden="true"
            >
              <path
                d="M23 9.5h6.5c3 0 5 2 5 5v11c0 3-2 5-5 5H23c-3 0-5-2-5-5v-11c0-3 2-5 5-5Z"
                fill="none"
                stroke="currentColor"
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth="3"
              />
              <path
                d="M25 16v8"
                fill="none"
                stroke="currentColor"
                strokeLinecap="round"
                strokeWidth="3"
              />
              <path
                d="M7 20h10M12 15v10"
                fill="none"
                stroke="currentColor"
                strokeLinecap="round"
                strokeWidth="3.2"
              />
            </svg>
          </button>
        </div>
      )}

      <footer className="fixed right-[clamp(18px,4vw,42px)] bottom-3.5 z-30 hidden md:block">
        <Button
          type="primary"
          size="large"
          className="bg-[#6fba2c]! border-[#6fba2c]! text-white! shadow-[0_5px_0_0_#5a9e1e]!"
          onClick={() => setStage('confirmed')}
        >
          {stage === 'confirmed' ? '已确定' : '确定方案'}
        </Button>
      </footer>

      {/* Mobile Bottom Navbar */}
      <nav className="fixed bottom-0 inset-x-0 z-40 md:hidden bg-[#fff9e8]/96 backdrop-blur-md border-t-2 border-[rgba(196,184,158,0.38)] px-4 py-3 flex items-center justify-around shadow-[0_-4px_16px_rgba(61,52,40,0.05)] pb-safe-bottom">
        {[
          {
            id: 'puzzle' as ColumnId,
            label: '拼图',
            icon: (
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
                <path d="M9 11l3 3L22 4" />
                <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
              </svg>
            ),
          },
          {
            id: 'merchant' as ColumnId,
            label: '商家',
            icon: (
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
                <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                <polyline points="9 22 9 12 15 12 15 22" />
              </svg>
            ),
          },
          {
            id: 'details' as ColumnId,
            label: '详情',
            icon: (
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
                <line x1="16" y1="13" x2="8" y2="13" />
                <line x1="16" y1="17" x2="8" y2="17" />
                <polyline points="10 9 9 9 8 9" />
              </svg>
            ),
          },
          {
            id: 'map' as ColumnId,
            label: '地图',
            icon: (
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
                <polygon points="3 6 9 3 15 6 21 3 21 18 15 21 9 18 3 21" />
                <line x1="9" y1="3" x2="9" y2="18" />
                <line x1="15" y1="6" x2="15" y2="21" />
              </svg>
            ),
          },
        ].map((item) => {
          const isActive = activeMobileTab === item.id
          return (
            <button
              key={item.id}
              type="button"
              className={`relative flex items-center gap-1.5 px-2.5 py-1.5 xs:px-3.5 xs:py-2 cursor-pointer transition-all duration-250 ease-in-out select-none border-none outline-none rounded-[24px] ${
                isActive
                  ? 'bg-[#0cc0b5] text-[#fff9e3] font-bold shadow-[0_3px_8px_rgba(12,192,181,0.25)]'
                  : 'text-[#794f27] bg-transparent hover:bg-[#19c8b91a]'
              }`}
              onClick={() => setActiveMobileTab(item.id)}
            >
              <div className={`w-[18px] h-[18px] flex items-center justify-center transition-transform duration-250 ${isActive ? 'scale-110' : ''}`}>
                {item.icon}
              </div>
              <span className="font-black tracking-wide text-[13px] whitespace-nowrap">{item.label}</span>

              {isActive && (
                <div className="absolute right-[-4px] top-[-4px] w-[16px] h-[16px] text-[#19c8b9] animate-leaf-wiggle pointer-events-none drop-shadow-[0_1.5px_1px_rgba(0,0,0,0.18)]">
                  <svg viewBox="0 0 24 24" fill="currentColor" className="w-full h-full">
                    <path d="M17 2H14C8.48 2 4 6.48 4 12C4 13.9 4.53 15.68 5.45 17.21C5.7 17.62 5.67 18.14 5.38 18.52L3.12 21.5C2.86 21.85 2.94 22.35 3.29 22.61C3.47 22.75 3.69 22.81 3.9 22.79C6.27 22.56 8.52 21.41 10.12 19.55C10.5 19.11 11.11 18.96 11.66 19.18C12.42 19.49 13.23 19.65 14 19.65C19.52 19.65 24 15.17 24 9.65V6.65C24 4.09 21.91 2 19.35 2H17ZM17.48 10.36L12.52 15.32C12.13 15.71 11.5 15.71 11.11 15.32C10.72 14.93 10.72 14.3 11.11 13.91L16.07 8.95C16.46 8.56 17.09 8.56 17.48 8.95C17.87 9.34 17.87 9.97 17.48 10.36Z" />
                  </svg>
                </div>
              )}
            </button>
          )
        })}
      </nav>
    </main>
  )
}

function ColumnHeader({
  column,
  onDragEnd,
  onDragStart,
  onRemove,
}: {
  column: ColumnId
  onDragEnd: () => void
  onDragStart: () => void
  onRemove: (column: ColumnId) => void
}) {
  return (
    <div
      className="hidden md:flex items-end justify-between gap-4 min-h-[70px] px-6 py-2.5 cursor-grab active:cursor-grabbing select-none"
      draggable={true}
      onDragEnd={onDragEnd}
      onDragStart={onDragStart}
    >
      <div>
        <span className="hidden md:inline-flex items-center min-h-[21px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black">拖拽排序</span>
        <h2 className="m-0 text-[#794f27] text-[23px] leading-tight font-black">{columnMeta[column].title}</h2>
        <p className="hidden m-1 text-[#9f927d] text-[13px] font-bold">{columnMeta[column].hint}</p>
      </div>
      {column !== 'puzzle' && (
        <button
          type="button"
          className="hidden md:block shrink-0 px-2 py-1.25 border-2 border-animal-border rounded-full bg-[#fff9e8] text-[#725d42] text-[13px] font-black cursor-pointer hover:-translate-y-0.5 hover:border-[#a89878] transition-all"
          onClick={() => onRemove(column)}
        >
          关闭
        </button>
      )}
    </div>
  )
}

function PuzzleColumn({
  draggingNodeId,
  dragOverNodeId,
  setDragOverNodeId,
  editingNodeId,
  nodeDraft,
  nodes,
  onApplyRewrite,
  onDragEnd,
  onDragStart,
  onDrop,
  onEdit,
  onOpenMerchant,
  onReplace,
  onSetNodeDraft,
  onMoveUp,
  onMoveDown,
}: {
  draggingNodeId: string | null
  dragOverNodeId: string | null
  setDragOverNodeId: (nodeId: string | null) => void
  editingNodeId: string | null
  nodeDraft: string
  nodes: PlanNode[]
  onApplyRewrite: (nodeId: string) => void
  onDragEnd: () => void
  onDragStart: (nodeId: string) => void
  onDrop: (nodeId: string) => void
  onEdit: (nodeId: string) => void
  onOpenMerchant: (place: string) => void
  onReplace: (nodeId: string) => void
  onSetNodeDraft: (value: string) => void
  onMoveUp: (nodeId: string) => void
  onMoveDown: (nodeId: string) => void
}) {
  return (
    <div
      data-puzzle-container="true"
      className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0"
      onDragOver={(event) => {
        if (draggingNodeId) {
          event.preventDefault()
        }
      }}
      onDrop={(event) => {
        if (draggingNodeId) {
          event.stopPropagation()
          onDrop('__end__')
        }
      }}
    >
      {nodes.map((node, index) => (
        <Card
          className={`relative grid grid-cols-[36px_minmax(0,1fr)] max-[640px]:grid-cols-[36px_minmax(0,1fr)] gap-3 min-h-[188px] max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0 cursor-grab active:cursor-grabbing select-none ${
            draggingNodeId === node.id ? 'opacity-60 bg-[#fff9e8] scale-[0.985]' : ''
          } ${
            dragOverNodeId === node.id && draggingNodeId !== node.id
              ? '!border-t-4 !border-t-[#f7cd67] bg-[#fffce8]'
              : ''
          }`}
          draggable={true}
          key={`${node.id}-${node.title}`}
          onDragEnd={onDragEnd}
          onDragOver={(event) => {
            if (draggingNodeId) {
              event.preventDefault()
            }
          }}
          onDragEnter={() => {
            if (draggingNodeId && draggingNodeId !== node.id) {
              setDragOverNodeId(node.id)
            }
          }}
          onDragLeave={() => {
            setDragOverNodeId(null)
          }}
          onDragStart={(event) => {
            event.stopPropagation()
            onDragStart(node.id)
          }}
          onDrop={(event) => {
            event.stopPropagation()
            onDrop(node.id)
          }}
        >


          {/* Subtle Jigsaw Puzzle Watermark SVG in Background */}
          <div className="absolute right-4 bottom-4 w-16 h-16 text-[#725d42]/6 pointer-events-none select-none z-0">
            <svg viewBox="0 0 24 24" fill="currentColor" className="w-full h-full">
              <path d="M20 12c0-1.1-.9-2-2-2V7c0-1.1-.9-2-2-2h-3c0-1.1-.9-2-2-2s-2 .9-2 2H6c-1.1 0-2 .9-2 2v3c1.1 0 2 .9 2 2s-.9 2-2 2v3c0 1.1.9 2 2 2h3c0 1.1.9 2 2 2s2-.9 2-2h3c1.1 0 2-.9 2-2v-3c1.1 0 2-.9 2-2z" />
            </svg>
          </div>

          <div className="relative z-10 flex flex-col items-center gap-1.5 shrink-0 select-none">
            <div className="grid place-items-center w-8 h-8 rounded-full bg-[#f7cd67] text-[#725d42] font-black shadow-[0_3px_0_#dba90e]">{index + 1}</div>
            <div className="flex flex-col gap-1 mt-1">
              <button
                type="button"
                disabled={index === 0}
                className="grid place-items-center w-6 h-6 border border-animal-border rounded bg-[#fff9e8] text-[#725d42] text-xs font-black cursor-pointer hover:bg-[#ffeea0] disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-[#fff9e8] transition-colors"
                onClick={(e) => { e.stopPropagation(); onMoveUp(node.id); }}
                title="向上移动"
              >
                ▲
              </button>
              <button
                type="button"
                disabled={index === nodes.length - 1}
                className="grid place-items-center w-6 h-6 border border-animal-border rounded bg-[#fff9e8] text-[#725d42] text-xs font-black cursor-pointer hover:bg-[#ffeea0] disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-[#fff9e8] transition-colors"
                onClick={(e) => { e.stopPropagation(); onMoveDown(node.id); }}
                title="向下移动"
              >
                ▼
              </button>
            </div>
          </div>
          <article className="relative z-10 flex flex-col min-w-0 flex-1">
            <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
              <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">{node.time}</strong>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start">{node.status}</span>
            </div>
            <h3 className="mt-1.25 mb-0 text-[#794f27] text-lg font-black leading-snug">{node.title}</h3>
            <button
              className="inline-flex w-fit mt-1.25 p-0 border-0 border-b-2 border-[#9a835a]/30 bg-transparent text-[#9a835a] font-black text-sm leading-snug text-left cursor-pointer hover:text-[#794f27] hover:border-b-[#f7cd67] transition-all"
              type="button"
              onClick={(event) => {
                event.stopPropagation()
                onOpenMerchant(node.place)
              }}
            >
              {node.place}
            </button>
            <p className="mt-1.25 mb-0 text-[#725d42] text-sm font-semibold leading-relaxed">{node.reason}</p>
            <div className="flex flex-wrap items-center gap-2 mt-2.5">
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">{node.audience}</span>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">{node.budget}</span>
            </div>

            {editingNodeId === node.id ? (
              <div className="grid grid-cols-[1fr_auto] max-[640px]:grid-cols-1 items-center gap-2 mt-2.5 pt-0">
                <Input
                  allowClear
                  value={nodeDraft}
                  placeholder="描述你想怎么改这一块"
                  onChange={(event) => onSetNodeDraft(event.target.value)}
                  onClear={() => onSetNodeDraft('')}
                />
                <Button
                  type="primary"
                  disabled={!nodeDraft.trim()}
                  onClick={() => onApplyRewrite(node.id)}
                >
                  生成
                </Button>
              </div>
            ) : (
              <div className="flex flex-wrap items-center gap-2 mt-2.5 pt-0">
                <Button
                  type="default"
                  size="small"
                  className="min-h-[30px]! px-[13px]! text-[12px]!"
                  onClick={() => onReplace(node.id)}
                >
                  换一个
                </Button>
                <Button
                  type="dashed"
                  size="small"
                  className="min-h-[30px]! px-[13px]! text-[12px]!"
                  onClick={() => onEdit(node.id)}
                >
                  描述修改
                </Button>
              </div>
            )}
          </article>
        </Card>
      ))}
    </div>
  )
}

function MerchantColumn({
  nodes,
  onSelectPlace,
  selectedPlace,
}: {
  nodes: PlanNode[]
  onSelectPlace: (place: string) => void
  selectedPlace: string | null
}) {
  const selectedNode =
    nodes.find((node) => node.place === selectedPlace) ?? nodes.find((node) => node.id !== 'start')
  const orderedNodes = selectedNode
    ? [selectedNode, ...nodes.filter((node) => node.place !== selectedNode.place)]
    : nodes

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0">
      {orderedNodes.map((node, index) => {
        const profile = merchantProfiles[node.place] ?? createFallbackMerchant(node)
        const isSelected = node.place === selectedNode?.place

        return (
          <Card
            className={`flex flex-col min-h-[188px] max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0 ${isSelected ? 'bg-[#fff9e8]!' : ''}`}
            key={`${node.id}-${node.place}`}
          >
            <article className="flex flex-col min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
                <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">{isSelected ? '正在查看' : node.time}</strong>
                <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start">{index === 0 ? '已选中' : node.status}</span>
              </div>
              <h3 className="mt-1.25 mb-0 text-[#794f27] text-lg font-black leading-snug">{node.place}</h3>
              <p className="mt-1.25 mb-0 text-[#725d42] text-sm font-semibold leading-relaxed">{profile.address}</p>
              <dl className="grid gap-1.75 mt-2.5">
                <div className="grid grid-cols-[42px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[13px] leading-[1.4]">营业</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[13px] leading-[1.4]">{profile.hours}</dd>
                </div>
                <div className="grid grid-cols-[42px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[13px] leading-[1.4]">排队</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[13px] leading-[1.4]">{profile.queue}</dd>
                </div>
                <div className="grid grid-cols-[42px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[13px] leading-[1.4]">预约</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[13px] leading-[1.4]">{profile.booking}</dd>
                </div>
                <div className="grid grid-cols-[42px_minmax(0,1fr)] gap-2 items-start">
                  <dt className="m-0 text-[#9a835a] font-black text-[13px] leading-[1.4]">电话</dt>
                  <dd className="m-0 text-[#725d42] font-bold text-[13px] leading-[1.4]">{profile.contact}</dd>
                </div>
              </dl>
              <div className="flex flex-wrap items-center gap-2 mt-2.5">
                {profile.tags.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap"
                  >
                    {tag}
                  </span>
                ))}
              </div>
              {!isSelected && (
                <div className="flex flex-wrap items-center gap-2 mt-2.5 pt-0">
                  <Button
                    type="default"
                    size="small"
                    className="min-h-[30px]! px-[13px]! text-[12px]!"
                    onClick={() => onSelectPlace(node.place)}
                  >
                    查看这个
                  </Button>
                </div>
              )}
            </article>
          </Card>
        )
      })}
    </div>
  )
}

function createFallbackMerchant(node: PlanNode): MerchantProfile {
  return {
    address: `${node.place} · 本地推荐点位`,
    queue: node.status.includes('预约') ? '建议先确认余位。' : '预计等待时间较短。',
    booking: node.details,
    hours: '以当天营业为准',
    contact: '待接入真实商家接口',
    tags: [node.audience, node.budget, node.status],
  }
}

function DetailsColumn({ nodes }: { nodes: PlanNode[] }) {
  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0">
      {nodes.map((node) => (
        <Card
          className="flex flex-col min-h-[188px] max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0"
          key={node.id}
        >
          <article className="flex flex-col min-w-0 flex-1">
            <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
              <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">{node.time} · {node.place}</strong>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start">{node.status}</span>
            </div>
            <h3 className="mt-1.25 mb-0 text-[#794f27] text-lg font-black leading-snug">{node.title}</h3>
            <p className="mt-1.25 mb-0 text-[#725d42] text-sm font-semibold leading-relaxed">{node.details}</p>
            <div className="flex flex-wrap items-center gap-2 mt-2.5">
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">{node.audience}</span>
              <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#fff3c4] text-[#725d42] text-[11px] font-black shrink-0 whitespace-nowrap">{node.budget}</span>
            </div>
          </article>
        </Card>
      ))}
    </div>
  )
}

type RouteSegmentInfo = {
  walking: { duration: number; distance: number } | null
  transit: { duration: number; transfers: number } | null
  driving: { duration: number; distance: number } | null
}

function MapColumn({ nodes }: { nodes: PlanNode[] }) {
  const mapRef = useRef<HTMLDivElement>(null)
  const mapInstanceRef = useRef<AMap.Map | null>(null)
  const overlaysRef = useRef<unknown[]>([])
  const [routeSegments, setRouteSegments] = useState<RouteSegmentInfo[]>([])
  const [loadingRoutes, setLoadingRoutes] = useState(false)
  const [mapReady, setMapReady] = useState(false)

  // Stable serialization of node coordinates for effect dependency
  const nodesKey = nodes.map((n) => `${n.id}:${n.lnglat[0]},${n.lnglat[1]}`).join('|')

  // Initialize map
  useEffect(() => {
    if (!mapRef.current || !window.AMap) return

    if (mapInstanceRef.current) {
      mapInstanceRef.current.destroy()
      mapInstanceRef.current = null
    }

    const map = new AMap.Map(mapRef.current, {
      zoom: 14,
      mapStyle: 'amap://styles/macaron',
      center: nodes[0]?.lnglat,
      features: ['bg', 'road', 'building', 'point'],
    })
    mapInstanceRef.current = map
    setMapReady(true)

    return () => {
      map.destroy()
      mapInstanceRef.current = null
      setMapReady(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Update markers & polyline when nodes change
  useEffect(() => {
    const map = mapInstanceRef.current
    if (!map || !mapReady) return

    // Clear previous overlays
    if (overlaysRef.current.length > 0) {
      map.remove(overlaysRef.current)
      overlaysRef.current = []
    }

    const newOverlays: unknown[] = []

    // Add numbered markers
    nodes.forEach((node, i) => {
      const marker = new AMap.Marker({
        position: node.lnglat,
        map,
        anchor: 'center',
        content: `<div style="
          display:grid; place-items:center;
          width:32px; height:32px;
          border-radius:50%;
          background:#f7cd67;
          color:#725d42;
          font-weight:900; font-size:14px;
          font-family:Nunito,sans-serif;
          box-shadow:0 3px 0 #dba90e, 0 6px 12px rgba(61,52,40,0.2);
          border:2.5px solid #fff;
        ">${i + 1}</div>`,
        offset: new AMap.Pixel(-16, -16),
      })
      newOverlays.push(marker)
    })

    // Draw route polyline
    if (nodes.length > 1) {
      const polyline = new AMap.Polyline({
        path: nodes.map((n) => n.lnglat),
        strokeColor: '#19c8b9',
        strokeWeight: 5,
        strokeStyle: 'solid',
        strokeOpacity: 0.85,
        lineJoin: 'round',
        lineCap: 'round',
        map,
      })
      newOverlays.push(polyline)

      // Dashed outline for depth
      const outlinePolyline = new AMap.Polyline({
        path: nodes.map((n) => n.lnglat),
        strokeColor: '#11a89b',
        strokeWeight: 8,
        strokeStyle: 'solid',
        strokeOpacity: 0.2,
        lineJoin: 'round',
        lineCap: 'round',
        map,
      })
      newOverlays.push(outlinePolyline)
    }

    overlaysRef.current = newOverlays

    // Fit view
    if (nodes.length > 0) {
      map.setFitView(undefined, false, [60, 60, 60, 60], 16)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodesKey, mapReady])

  // Fetch route info for each segment
  useEffect(() => {
    if (!window.AMap || nodes.length < 2) {
      setRouteSegments([])
      return
    }

    setLoadingRoutes(true)
    const segments: RouteSegmentInfo[] = Array.from(
      { length: nodes.length - 1 },
      () => ({ walking: null, transit: null, driving: null }),
    )
    let completed = 0
    const total = (nodes.length - 1) * 3

    function checkDone() {
      completed++
      if (completed >= total) {
        setRouteSegments([...segments])
        setLoadingRoutes(false)
      }
    }

    for (let i = 0; i < nodes.length - 1; i++) {
      const origin = nodes[i].lnglat
      const dest = nodes[i + 1].lnglat

      // Walking
      const walking = new AMap.Walking({ autoFitView: false, hideMarkers: true })
      walking.search(origin, dest, (status, result) => {
        if (status === 'complete' && result.routes?.[0]) {
          segments[i].walking = {
            duration: Math.round(result.routes[0].time / 60),
            distance: Math.round(result.routes[0].distance / 100) / 10,
          }
        }
        checkDone()
      })

      // Transit
      const transit = new AMap.Transfer({ city: '上海', autoFitView: false, hideMarkers: true })
      transit.search(origin, dest, (status, result) => {
        if (status === 'complete' && result.plans?.[0]) {
          segments[i].transit = {
            duration: Math.round(result.plans[0].time / 60),
            transfers: Math.max(0, (result.plans[0].segments?.filter((s) => s.transit_mode === 'BUS' || s.bus)?.length ?? 1) - 1),
          }
        }
        checkDone()
      })

      // Driving
      const driving = new AMap.Driving({ autoFitView: false, hideMarkers: true })
      driving.search(origin, dest, (status, result) => {
        if (status === 'complete' && result.routes?.[0]) {
          segments[i].driving = {
            duration: Math.round(result.routes[0].time / 60),
            distance: Math.round(result.routes[0].distance / 100) / 10,
          }
        }
        checkDone()
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodesKey])

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0">
      {/* Map Container */}
      <Card className="flex flex-col shrink-0 p-3 px-4 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible hover:!translate-y-0">
        <div
          ref={mapRef}
          className="w-full h-[300px] rounded-[20px] border-2 border-animal-border overflow-hidden shadow-[0_3px_0_0_#d4c9b4]"
        />
        {!window.AMap && (
          <div className="flex flex-col items-center justify-center absolute inset-0 bg-[#eef7df] rounded-[20px] text-center p-6">
            <div className="text-3xl mb-2">🗺️</div>
            <p className="text-[#794f27] font-black text-sm">地图加载中…</p>
            <p className="text-[#9a835a] text-xs mt-1">请确认高德地图 API 安全密钥已配置</p>
          </div>
        )}
      </Card>

      {/* Route overview mini-timeline */}
      <Card className="flex flex-col shrink-0 p-3 px-4 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] hover:!translate-y-0">
        <div className="relative grid gap-1.25 p-[8px_10px] border-2 border-animal-border rounded-[16px] bg-[#eef7df]/60 before:content-[''] before:absolute before:top-5 before:bottom-5 before:left-[21px] before:w-[3px] before:rounded-full before:bg-[#19c8b9]">
          {nodes.map((node, index) => (
            <div className="relative z-10 flex items-center gap-2" key={node.id}>
              <span className="grid place-items-center w-5 h-5 rounded-full bg-[#82d5bb] text-[#0f332e] text-[10px] font-black shadow-[0_2px_0_#11a89b]">{index + 1}</span>
              <p className="m-0 px-[8px] py-0.5 rounded-full bg-[#fff9e8] text-[#725d42] text-[10px] font-black">{node.place}</p>
            </div>
          ))}
        </div>
      </Card>

      {/* Route segment cards */}
      {nodes.slice(0, -1).map((node, index) => {
        const nextNode = nodes[index + 1]
        const segment = routeSegments[index]
        const isLoading = loadingRoutes && !segment

        return (
          <Card
            className="flex flex-col max-[640px]:px-4 max-[640px]:py-[15px] shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] transition-all duration-200 overflow-visible last:border-b-0 hover:!translate-y-0"
            key={`route-${node.id}-${nextNode.id}`}
          >
            <article className="flex flex-col min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2.5 min-w-0 max-[640px]:flex-col max-[640px]:items-stretch">
                <strong className="min-w-0 overflow-hidden text-[#794f27] text-sm font-black text-ellipsis whitespace-nowrap">{node.time} → {nextNode.time}</strong>
                <span className="inline-flex items-center min-h-[22px] px-2 rounded-full bg-[#e6f9f6] text-[#11a89b] text-[11px] font-black shrink-0 whitespace-nowrap max-[640px]:self-start">移动</span>
              </div>
              <h3 className="mt-1.25 mb-0 text-[#794f27] text-lg font-black leading-snug">
                {node.place} → {nextNode.place}
              </h3>

              {isLoading ? (
                <div className="flex flex-col gap-2 mt-3">
                  {[1, 2, 3].map((i) => (
                    <div key={i} className="h-[34px] rounded-full bg-[#f0e8d8] animate-pulse" />
                  ))}
                </div>
              ) : segment ? (
                <div className="flex flex-col gap-1.5 mt-3">
                  {segment.walking && (
                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-[#e6f9f6] border border-[#82d5bb]/40">
                      <span className="text-base leading-none">🚶</span>
                      <span className="text-[#11a89b] text-[12px] font-black">步行</span>
                      <span className="text-[#725d42] text-[12px] font-bold">{segment.walking.duration} 分钟</span>
                      <span className="text-[#9a835a] text-[11px]">· {segment.walking.distance} km</span>
                    </div>
                  )}
                  {segment.transit && (
                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-[#e8ecfd] border border-[#889df0]/30">
                      <span className="text-base leading-none">🚌</span>
                      <span className="text-[#5a6fbf] text-[12px] font-black">公交</span>
                      <span className="text-[#725d42] text-[12px] font-bold">{segment.transit.duration} 分钟</span>
                      <span className="text-[#9a835a] text-[11px]">· 换乘 {segment.transit.transfers} 次</span>
                    </div>
                  )}
                  {segment.driving && (
                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-[#fff3c4] border border-[#f7cd67]/40">
                      <span className="text-base leading-none">🚗</span>
                      <span className="text-[#9a835a] text-[12px] font-black">驾车</span>
                      <span className="text-[#725d42] text-[12px] font-bold">{segment.driving.duration} 分钟</span>
                      <span className="text-[#9a835a] text-[11px]">· {segment.driving.distance} km</span>
                    </div>
                  )}
                  {!segment.walking && !segment.transit && !segment.driving && (
                    <p className="mt-1 text-[#9a835a] text-sm font-semibold">暂未获取到路线数据</p>
                  )}
                </div>
              ) : (
                <p className="mt-2 text-[#9a835a] text-sm font-semibold">路线数据加载中…</p>
              )}
            </article>
          </Card>
        )
      })}
    </div>
  )
}

export default App
