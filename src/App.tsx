import { useEffect, useMemo, useRef, useState } from 'react'
import type { DragEvent, FormEvent } from 'react'
import { Button, Card, Input, Select } from 'animal-island-ui'
import './App.css'

type Stage = 'intro' | 'planning' | 'confirmed'
type ColumnId = 'puzzle' | 'merchant' | 'details' | 'map'

type PlanNode = {
  id: string
  time: string
  title: string
  place: string
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
    audience: '拍照 / 散步 / 遛娃',
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
      audience: '朋友聚会友好',
      reason: '上菜快、选择简单，大家口味分歧小。',
      budget: '人均 ¥70-100',
      status: '好分食',
      details: '可提前排号，儿童座椅较少。',
    },
  ],
}

const routeTimes = ['约 24 分钟', '步行 8 分钟', '步行 10 分钟', '打车 12 分钟']
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
  const [isColumnMenuOpen, setIsColumnMenuOpen] = useState(false)
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null)
  const [selectedMerchantPlace, setSelectedMerchantPlace] = useState<string | null>(null)
  const [nodeDraft, setNodeDraft] = useState('')
  const addColumnMenuRef = useRef<HTMLDivElement>(null)

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
    if (!isColumnMenuOpen) return
    window.setTimeout(() => {
      const trigger = addColumnMenuRef.current?.querySelector<HTMLElement>(
        '[class*="animal-trigger"]',
      )
      trigger?.click()
    }, 0)
  }, [isColumnMenuOpen])

  function submitRequirement(event?: FormEvent) {
    event?.preventDefault()
    const text = draft.trim()
    if (!text) return
    setRequirement(text)
    setPlanNodes(basePlan)
    setColumns(['puzzle'])
    setSelectedMerchantPlace(null)
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
  }

  function removeColumn(columnId: ColumnId) {
    if (columnId === 'puzzle') return
    setColumns((current) => current.filter((column) => column !== columnId))
  }

  function handleColumnDrop(targetColumn: ColumnId) {
    if (!draggingColumn || draggingColumn === targetColumn) return
    setColumns((current) => {
      const next = current.filter((column) => column !== draggingColumn)
      const targetIndex = next.indexOf(targetColumn)
      next.splice(targetIndex, 0, draggingColumn)
      return next
    })
    setDraggingColumn(null)
  }

  function handleNodeDrop(targetNodeId: string) {
    if (!draggingNodeId || draggingNodeId === targetNodeId) return
    setPlanNodes((nodes) => {
      const fromIndex = nodes.findIndex((node) => node.id === draggingNodeId)
      const toIndex = nodes.findIndex((node) => node.id === targetNodeId)
      if (fromIndex < 0 || toIndex < 0) return nodes
      const next = [...nodes]
      const [movedNode] = next.splice(fromIndex, 1)
      next.splice(toIndex, 0, movedNode)
      return next
    })
    setDraggingNodeId(null)
  }

  function allowDrop(event: DragEvent<HTMLElement>) {
    event.preventDefault()
  }

  if (stage === 'intro') {
    return (
      <main className="intro-screen">
        <div className="intro-topbar">
          <span>Plan Pal</span>
          <button type="button">Demo</button>
        </div>

        <section className="ai-home">
          <div className="logo-spark" aria-hidden="true">
            ✳
          </div>
          <h1>今天想把什么安排好？</h1>

          <form className="ai-composer" onSubmit={submitRequirement}>
            <textarea
              value={draft}
              placeholder="告诉我时间、人数、地点偏好和限制..."
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  submitRequirement(event)
                }
              }}
            />
            <div className="composer-actions">
              <button type="button" aria-label="添加附件">
                +
              </button>
              <div>
                <span>本地规划</span>
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

          <div className="quick-prompts" aria-label="快捷需求">
            {examplePrompts.map((prompt) => (
              <button
                type="button"
                key={prompt}
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
    <main className="planner-screen">
      <header className="planner-topbar">
        <div>
          <strong>为你推荐</strong>
          <span>{requirement}</span>
        </div>
        <div className="topbar-actions">
          <Button type="default" size="small" onClick={() => setStage('intro')}>
            重新输入
          </Button>
        </div>
      </header>

      <section className={`feed-board column-count-${columns.length}`}>
        {columns.map((column) => (
          <section
            className={`feed-lane ${draggingColumn === column ? 'is-dragging' : ''}`}
            key={column}
            onDragOver={allowDrop}
            onDrop={() => handleColumnDrop(column)}
          >
            <ColumnHeader
              column={column}
              onDragEnd={() => setDraggingColumn(null)}
              onDragStart={() => setDraggingColumn(column)}
              onRemove={removeColumn}
            />
            <div className="feed-column">
              {column === 'puzzle' && (
                <PuzzleColumn
                  draggingNodeId={draggingNodeId}
                  editingNodeId={editingNodeId}
                  nodeDraft={nodeDraft}
                  nodes={scheduledNodes}
                  onApplyRewrite={applyNodeRewrite}
                  onDragEnd={() => setDraggingNodeId(null)}
                  onDragStart={setDraggingNodeId}
                  onDrop={handleNodeDrop}
                  onEdit={(nodeId) => {
                    setEditingNodeId(nodeId)
                    setNodeDraft('')
                  }}
                  onOpenMerchant={openMerchantColumn}
                  onReplace={replaceNode}
                  onSetNodeDraft={setNodeDraft}
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
        ))}
      </section>

      {closedColumns.length > 0 && (
        <div className="floating-add-column">
          {isColumnMenuOpen && (
            <div className="add-column-menu" ref={addColumnMenuRef}>
              <Select
                options={columnOptions}
                placeholder="选择要添加的列"
                value=""
                onChange={(key) => addColumn(key as ColumnId)}
              />
            </div>
          )}
          <button
            className="add-column-trigger"
            type="button"
            aria-label="添加列"
            aria-expanded={isColumnMenuOpen}
            onClick={() => setIsColumnMenuOpen((open) => !open)}
          >
            <svg
              className="add-column-icon"
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

      <footer className="confirm-bar">
        {stage === 'confirmed' ? (
          <span>已生成可执行计划：出发、预约、吃饭和路线都排好了。</span>
        ) : (
          <span>拖拽拼图会自动重排时间；拖拽列标题可调整列顺序。</span>
        )}
        <Button
          type="primary"
          size="large"
          className="confirm-button"
          onClick={() => setStage('confirmed')}
        >
          确定方案
        </Button>
      </footer>
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
      className="feed-column-head"
      draggable
      onDragEnd={onDragEnd}
      onDragStart={onDragStart}
    >
      <div>
        <span>拖拽排序</span>
        <h2>{columnMeta[column].title}</h2>
        <p>{columnMeta[column].hint}</p>
      </div>
      {column !== 'puzzle' && (
        <button type="button" onClick={() => onRemove(column)}>
          关闭
        </button>
      )}
    </div>
  )
}

function PuzzleColumn({
  draggingNodeId,
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
}: {
  draggingNodeId: string | null
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
}) {
  return (
    <div className="thread-list">
      {nodes.map((node, index) => (
        <Card
          className={`thread-card puzzle-card ${
            draggingNodeId === node.id ? 'is-node-dragging' : ''
          }`}
          draggable
          key={`${node.id}-${node.title}`}
          onDragEnd={onDragEnd}
          onDragOver={(event) => event.preventDefault()}
          onDragStart={(event) => {
            event.stopPropagation()
            onDragStart(node.id)
          }}
          onDrop={(event) => {
            event.stopPropagation()
            onDrop(node.id)
          }}
        >
          <div className="avatar-dot">{index + 1}</div>
          <article>
            <div className="thread-meta">
              <strong>{node.time}</strong>
              <span>{node.status}</span>
            </div>
            <h3>{node.title}</h3>
            <button
              className="place place-button"
              type="button"
              onClick={(event) => {
                event.stopPropagation()
                onOpenMerchant(node.place)
              }}
            >
              {node.place}
            </button>
            <p>{node.reason}</p>
            <div className="meta-row">
              <span>{node.audience}</span>
              <span>{node.budget}</span>
            </div>

            {editingNodeId === node.id ? (
              <div className="rewrite-box">
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
              <div className="action-row">
                <Button type="default" size="small" onClick={() => onReplace(node.id)}>
                  换一个
                </Button>
                <Button type="dashed" size="small" onClick={() => onEdit(node.id)}>
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
    <div className="thread-list">
      {orderedNodes.map((node, index) => {
        const profile = merchantProfiles[node.place] ?? createFallbackMerchant(node)
        const isSelected = node.place === selectedNode?.place

        return (
          <Card
            className={`thread-card merchant-card ${isSelected ? 'is-selected' : ''}`}
            key={`${node.id}-${node.place}`}
          >
            <article>
              <div className="thread-meta">
                <strong>{isSelected ? '正在查看' : node.time}</strong>
                <span>{index === 0 ? '已选中' : node.status}</span>
              </div>
              <h3>{node.place}</h3>
              <p>{profile.address}</p>
              <dl className="merchant-facts">
                <div>
                  <dt>营业</dt>
                  <dd>{profile.hours}</dd>
                </div>
                <div>
                  <dt>排队</dt>
                  <dd>{profile.queue}</dd>
                </div>
                <div>
                  <dt>预约</dt>
                  <dd>{profile.booking}</dd>
                </div>
                <div>
                  <dt>电话</dt>
                  <dd>{profile.contact}</dd>
                </div>
              </dl>
              <div className="meta-row">
                {profile.tags.map((tag) => (
                  <span key={tag}>{tag}</span>
                ))}
              </div>
              {!isSelected && (
                <div className="action-row">
                  <Button
                    type="default"
                    size="small"
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
    <div className="thread-list">
      {nodes.map((node) => (
        <Card className="thread-card detail-card" key={node.id}>
          <article>
            <div className="thread-meta">
              <strong>{node.time} · {node.place}</strong>
              <span>{node.status}</span>
            </div>
            <h3>{node.title}</h3>
            <p>{node.details}</p>
            <div className="meta-row">
              <span>{node.audience}</span>
              <span>{node.budget}</span>
            </div>
          </article>
        </Card>
      ))}
    </div>
  )
}

function MapColumn({ nodes }: { nodes: PlanNode[] }) {
  return (
    <div className="thread-list">
      <Card className="thread-card map-card">
        <div className="mini-map">
          {nodes.map((node, index) => (
            <div className="map-point" key={node.id}>
              <span>{index + 1}</span>
              <p>{node.place}</p>
            </div>
          ))}
        </div>
      </Card>
      {routeTimes.map((time, index) => (
        <Card className="thread-card route-card" key={`${time}-${index}`}>
          <article>
            <div className="thread-meta">
              <strong>{time}</strong>
              <span>移动</span>
            </div>
            <h3>
              {nodes[index]?.place} → {nodes[index + 1]?.place}
            </h3>
            <p>路线会按当前拼图顺序自动更新，后续可接真实地图 SDK。</p>
          </article>
        </Card>
      ))}
    </div>
  )
}

export default App
