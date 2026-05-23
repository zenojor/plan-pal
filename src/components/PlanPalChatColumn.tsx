import { Button, Card, Input } from 'animal-island-ui'
import type { ChatMessage } from '../types/plan'

type PlanPalChatColumnProps = {
  draft: string
  isDisabled?: boolean
  messages: ChatMessage[]
  onDraftChange: (value: string) => void
  onSend: () => void
}

export function PlanPalChatColumn({
  draft,
  isDisabled = false,
  messages,
  onDraftChange,
  onSend,
}: PlanPalChatColumnProps) {
  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-hidden bg-[#f7f3df]">
      <div className="flex-1 min-h-0 overflow-y-auto custom-scrollbar p-4 pb-3 space-y-3">
        {messages.length === 0 && (
          <Card className="rounded-[24px]! border-2! border-[#c4b89e]! bg-[#fff9e8]! p-4! text-[#725d42]! shadow-[0_4px_0_0_#d4c9b4]! hover:!translate-y-0">
            <span className="inline-flex rounded-full bg-[#e6f9f6] px-3 py-1 text-[12px] font-black text-[#11a89b]">
              PlanPal
            </span>
            <h3 className="m-0 mt-2 text-[#794f27] text-lg font-black">和我说你想怎么改</h3>
            <p className="m-0 mt-1 text-sm font-semibold leading-relaxed">
              比如“吃饭太远，换个近一点的烧烤”或“我想喝酒，要安静一点的 bar”。我会只调整相关拼图和相邻交通。
            </p>
          </Card>
        )}

        {messages.map((message) => (
          <div
            key={message.id}
            className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            <div
              className={`max-w-[86%] rounded-[22px] border-2 px-4 py-3 text-sm font-bold leading-relaxed shadow-[0_3px_0_0_#d4c9b4] ${
                message.role === 'user'
                  ? 'border-[#82d5bb] bg-[#e6f9f6] text-[#0f4c46]'
                  : 'border-[#c4b89e] bg-[#fff9e8] text-[#725d42]'
              }`}
            >
              {message.content}
            </div>
          </div>
        ))}
      </div>

      <div className="border-t-2 border-[#c4b89e]/60 bg-[#fff9e8] p-3">
        <div className="grid grid-cols-[1fr_auto] gap-2">
          <Input
            allowClear
            value={draft}
            disabled={isDisabled}
            placeholder="告诉 PlanPal 想换什么..."
            onChange={(event) => onDraftChange(event.target.value)}
            onClear={() => onDraftChange('')}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                onSend()
              }
            }}
          />
          <Button
            type="primary"
            disabled={isDisabled || !draft.trim()}
            className="bg-[#ffcc00]! border-[#ffcc00]! text-[#725d42]! shadow-[0_4px_0_0_#dba90e]!"
            onClick={onSend}
          >
            发送
          </Button>
        </div>
      </div>
    </div>
  )
}
