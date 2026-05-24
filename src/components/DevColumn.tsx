import { Card } from 'animal-island-ui'
import { useEffect, useRef } from 'react'
import type { AgentPlanResponse, AgentPlanStreamEvent } from '../api/agent'
import type { PlanNode } from '../types/plan'

type DevColumnProps = {
  plan: AgentPlanResponse | null
  nodes: PlanNode[]
  events: AgentPlanStreamEvent[]
}

export function DevColumn({ plan, nodes, events }: DevColumnProps) {
  const consoleBodyRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (consoleBodyRef.current) {
      consoleBodyRef.current.scrollTop = consoleBodyRef.current.scrollHeight
    }
  }, [events])

  const intent = plan?.intent

  const getBadgeColor = (type: string) => {
    switch (type) {
      case 'START':
        return 'bg-[#e2f0d9] text-[#385723]'
      case 'INTENT':
        return 'bg-[#fff2cc] text-[#7f6000]'
      case 'THOUGHT':
        return 'bg-[#ddebf7] text-[#1f4e78]'
      case 'ACTION':
        return 'bg-[#fce4d6] text-[#c65911]'
      case 'OBSERVATION':
        return 'bg-[#e1dfdd] text-[#3b3a39]'
      case 'PLAN_STEP':
        return 'bg-[#e8f7f5] text-[#0f6c5f]'
      case 'FINISH':
        return 'bg-[#f2e6ff] text-[#6000b3]'
      case 'ERROR':
        return 'bg-[#fde9d9] text-[#c00000]'
      default:
        return 'bg-[#f3f2f1] text-[#323130]'
    }
  }

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto overscroll-contain custom-scrollbar pb-[100px] md:pb-0 gap-4 p-4 bg-[#f7f3df]">
      {/* 1. System Config Dashboard */}
      <div className="flex flex-col shrink-0 px-4 py-3 border-2 border-[#c4b89e] rounded-[22px] bg-[#fff9e8] text-[#725d42] shadow-[0_3px_0_0_#d4c9b4] hover:!translate-y-0 transition-all duration-200">
        <h3 className="m-0 mb-3 text-[#794f27] text-md font-black flex items-center gap-2">
          <span>⚙️</span> 系统环境配置
        </h3>
        <div className="grid grid-cols-2 gap-2 text-xs">
          <div className="p-2 rounded-xl bg-[#efebe0]">
            <p className="m-0 text-[#9a835a] font-bold">POI 引擎模式</p>
            <p className="m-0 mt-0.5 text-[#725d42] font-black text-sm">Sandbox (离线模拟)</p>
          </div>
          <div className="p-2 rounded-xl bg-[#efebe0]">
            <p className="m-0 text-[#9a835a] font-bold">后端状态</p>
            <p className="m-0 mt-0.5 text-[#2e7d32] font-black text-sm">服务运行正常 (8081)</p>
          </div>
          <div className="p-2 rounded-xl bg-[#efebe0]">
            <p className="m-0 text-[#9a835a] font-bold">推荐策略</p>
            <p className="m-0 mt-0.5 text-[#725d42] font-black text-sm">基于标签启发排程</p>
          </div>
          <div className="p-2 rounded-xl bg-[#efebe0]">
            <p className="m-0 text-[#9a835a] font-bold">最大推理步数</p>
            <p className="m-0 mt-0.5 text-[#725d42] font-black text-sm">20 步 (ReAct 熔断器)</p>
          </div>
        </div>
      </div>

      <div className="flex flex-col shrink-0 px-4 py-3 border-2 border-[#c4b89e] rounded-[22px] bg-[#fff9e8] text-[#725d42] shadow-[0_3px_0_0_#d4c9b4] hover:!translate-y-0 transition-all duration-200">
        <h3 className="m-0 mb-3 text-[#794f27] text-md font-black flex items-center gap-2">
          <span>🎯</span> 规划结构化意图
        </h3>
        {intent ? (
          <div className="flex flex-col gap-2.5 text-xs">
            <div className="flex justify-between items-center border-b border-[#f1efe9] pb-1.5">
              <span className="text-[#9a835a]">场景类型 (sceneType)</span>
              <span className="px-2 py-0.5 rounded-full bg-[#eef7df] text-[#426a15] font-black">{intent.sceneType}</span>
            </div>
            <div className="flex justify-between items-center border-b border-[#f1efe9] pb-1.5">
              <span className="text-[#9a835a]">出行人数 (headcount)</span>
              <span className="font-black text-[#725d42]">{intent.headcount} 人</span>
            </div>
            <div className="flex justify-between items-center border-b border-[#f1efe9] pb-1.5">
              <span className="text-[#9a835a]">时间预算 (totalMinutes)</span>
              <span className="font-black text-[#725d42]">{intent.totalMinutes} 分钟 ({intent.startTime} - {intent.endTime})</span>
            </div>
            <div className="flex justify-between items-center border-b border-[#f1efe9] pb-1.5">
              <span className="text-[#9a835a]">活动节奏 (pace)</span>
              <span className="px-2 py-0.5 rounded-full bg-[#fde9d9] text-[#c65911] font-black">{intent.pace}</span>
            </div>
            <div className="flex justify-between items-center border-b border-[#f1efe9] pb-1.5">
              <span className="text-[#9a835a]">预算档次 (budgetLevel)</span>
              <span className="px-2 py-0.5 rounded-full bg-[#eef7df] text-[#426a15] font-black">{intent.budgetLevel}</span>
            </div>
            <div className="flex justify-between items-center border-b border-[#f1efe9] pb-1.5">
              <span className="text-[#9a835a]">携带儿童 (hasChildren)</span>
              <span className="font-black text-[#725d42]">{intent.hasChildren ? '👶 是' : '❌ 否'}</span>
            </div>
            {intent.avoid?.length > 0 && (
              <div className="flex flex-col gap-1 border-b border-[#f1efe9] pb-1.5">
                <span className="text-[#9a835a]">规避偏好 (avoid)</span>
                <div className="flex flex-wrap gap-1">
                  {intent.avoid.map((a: string) => (
                    <span key={a} className="px-1.5 py-0.5 rounded bg-[#efebe0] text-[#725d42] text-[10px]">{a}</span>
                  ))}
                </div>
              </div>
            )}
            {intent.mustHave?.length > 0 && (
              <div className="flex flex-col gap-1 border-b border-[#f1efe9] pb-1.5">
                <span className="text-[#9a835a]">必须项 (mustHave)</span>
                <div className="flex flex-wrap gap-1">
                  {intent.mustHave.map((m: string) => (
                    <span key={m} className="px-1.5 py-0.5 rounded bg-[#eef7df] text-[#426a15] text-[10px]">{m}</span>
                  ))}
                </div>
              </div>
            )}
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center p-6 text-center text-xs text-[#9a835a] bg-[#efebe0] rounded-xl border border-dashed border-animal-border">
            <span className="text-2xl mb-1">💤</span>
            <span>当前无活跃规划方案，请发起规划</span>
          </div>
        )}
      </div>

      {/* 3. Real-time SSE Events terminal console */}
      <div className="flex-1 flex flex-col min-h-[300px] border-2 border-animal-border rounded-[24px] bg-[#1e1e1e] text-[#d4d4d4] font-mono text-[11px] overflow-hidden shadow-inner">
        <div className="flex justify-between items-center px-4 py-2 bg-[#2d2d2d] border-b border-[#3c3c3c] shrink-0 text-[10px] text-[#8c8c8c] font-sans font-bold">
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full bg-[#ff5f56]" />
            <span className="w-2.5 h-2.5 rounded-full bg-[#ffbd2e]" />
            <span className="w-2.5 h-2.5 rounded-full bg-[#27c93f]" />
            <span className="ml-1 text-[#aaaaaa]">SSE Agent 思考控制台</span>
          </div>
          <span>日志长度: {events.length}</span>
        </div>
        <div ref={consoleBodyRef} className="flex-1 overflow-y-auto p-3 space-y-2.5 custom-scrollbar bg-[#1a1a1a]">
          {events.length > 0 ? (
            events.map((event, index) => {
              if (event.type === 'DIVIDER') {
                return (
                  <div key={index} className="py-4 flex flex-col items-center justify-center gap-1.5 border-t border-b border-dashed border-[#555]/30 my-4 select-none">
                    <div className="flex items-center gap-2">
                      <span className="h-[1px] w-8 bg-[#555]/30" />
                      <span className="text-[#8a8a8a] text-[9px] tracking-widest font-black uppercase">SYSTEM UPDATE</span>
                      <span className="h-[1px] w-8 bg-[#555]/30" />
                    </div>
                    <p className="m-0 text-[#f7cd67] text-[11px] font-black text-center max-w-[90%] leading-relaxed">{event.content}</p>
                  </div>
                )
              }

              return (
                <div key={index} className="border-b border-[#2d2d2d] pb-2 last:border-0">
                  <div className="flex items-center gap-2 mb-1.5">
                    <span className="text-[#858585]">[{index + 1}]</span>
                    <span className={`px-1.5 py-0.25 rounded text-[9px] font-black tracking-wider ${getBadgeColor(event.type)}`}>
                      {event.type}
                    </span>
                    {event.step > 0 && <span className="text-[#007acc] font-black">Step {event.step}</span>}
                  </div>
                  <p className="m-0 pl-4 text-[#e1dbcb] leading-relaxed break-all whitespace-pre-wrap">{event.content}</p>
                  {event.degradationNote && (
                    <div className="mt-1 ml-4 p-1.5 rounded bg-[#f57c00]/10 border-l-2 border-[#f57c00] text-[#f57c00] text-[10px]">
                      ⚠️ 规划降级: {event.degradationNote}
                    </div>
                  )}
                </div>
              )
            })
          ) : (
            <div className="h-full flex flex-col items-center justify-center p-6 text-center text-[#858585]">
              <span className="text-xl mb-1.5">📟</span>
              <span>控制台就绪。发起规划后，实时推理和思考流将在这里展现...</span>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
