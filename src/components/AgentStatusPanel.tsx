import { Card } from 'animal-island-ui'
import type { AgentRun } from '../types/agent'

type AgentStatusPanelProps = {
  run: AgentRun | null
  isLoading: boolean
  error: string | null
}

const statusText = {
  thinking: '思考中',
  planning: '规划中',
  ready: '方案已生成',
  executing: '执行中',
  done: '已完成',
  failed: '失败',
  needs_more_info: '需要补充信息',
}

export function AgentStatusPanel({ run, isLoading, error }: AgentStatusPanelProps) {
  return (
    <Card className="flex flex-col shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#fff9e8] text-[#725d42] hover:!translate-y-0">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <strong className="block text-[#794f27] text-base font-black">Agent 调用链</strong>
          <p className="m-0 mt-1 text-[#9a835a] text-xs font-bold">
            {isLoading ? '正在调用 FastAPI + LangGraph...' : run ? statusText[run.status] : '等待输入'}
          </p>
        </div>
        {run && (
          <span className="shrink-0 rounded-full bg-[#e6f9f6] px-2 py-1 text-[11px] font-black text-[#11a89b]">
            {run.tool_calls.length} 步
          </span>
        )}
      </div>

      {error && (
        <p className="mt-3 mb-0 rounded-[14px] bg-[#fff0e8] px-3 py-2 text-sm font-bold text-[#a25532]">
          {error}
        </p>
      )}

      {run?.message && (
        <p className="mt-3 mb-0 text-sm font-bold leading-relaxed text-[#725d42]">{run.message}</p>
      )}

      {run && run.tool_calls.length > 0 && (
        <div className="mt-3 grid gap-2">
          {run.tool_calls.map((call, index) => (
            <div
              key={`${call.tool_name}-${index}`}
              className="rounded-[14px] border border-animal-border-light bg-[#f7f3df] px-3 py-2"
            >
              <div className="flex items-center justify-between gap-2">
                <strong className="text-[13px] font-black text-[#794f27]">
                  {index + 1}. {call.tool_name}
                </strong>
                <span className="text-[11px] font-black text-[#9a835a]">
                  {call.status} · {call.duration_ms}ms
                </span>
              </div>
              <p className="m-0 mt-1 text-xs font-bold leading-relaxed text-[#725d42]">
                {call.output_summary}
              </p>
            </div>
          ))}
        </div>
      )}
    </Card>
  )
}
