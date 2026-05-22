import { Button, Card } from 'animal-island-ui'
import type { ExecutionAction } from '../types/agent'

type ExecutionActionsProps = {
  actions: ExecutionAction[]
  executingActionId: string | null
  onExecute: (actionId: string) => void
}

const actionStatusText = {
  pending: '待执行',
  completed: '已完成',
  failed: '失败',
}

export function ExecutionActions({
  actions,
  executingActionId,
  onExecute,
}: ExecutionActionsProps) {
  if (actions.length === 0) return null

  return (
    <Card className="flex flex-col shrink-0 p-4 px-5 border-0 border-b-2 border-animal-border-light rounded-none bg-[#f7f3df] text-[#725d42] hover:!translate-y-0">
      <strong className="text-[#794f27] text-base font-black">可执行动作</strong>
      <div className="mt-3 grid gap-2">
        {actions.map((action) => (
          <div
            key={action.id}
            className="grid grid-cols-[1fr_auto] max-[640px]:grid-cols-1 gap-2 rounded-[14px] border border-animal-border-light bg-[#fff9e8] px-3 py-2"
          >
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <strong className="text-sm font-black text-[#794f27]">{action.label}</strong>
                <span className="rounded-full bg-[#e6f9f6] px-2 py-0.5 text-[11px] font-black text-[#11a89b]">
                  {actionStatusText[action.status]}
                </span>
              </div>
              <p className="m-0 mt-1 text-xs font-bold leading-relaxed text-[#725d42]">
                {action.status === 'completed' ? action.confirm_text : action.target}
              </p>
            </div>
            <Button
              type={action.status === 'completed' ? 'default' : 'primary'}
              size="small"
              disabled={action.status === 'completed' || executingActionId === action.id}
              onClick={() => onExecute(action.id)}
            >
              {executingActionId === action.id ? '执行中' : action.status === 'completed' ? '完成' : '执行'}
            </Button>
          </div>
        ))}
      </div>
    </Card>
  )
}
