import { Button, Card, Input } from 'animal-island-ui'
import { useState } from 'react'
import type { ChatMessage } from '../types/plan'

type PlanPalChatColumnProps = {
  draft: string
  isDisabled?: boolean
  messages: ChatMessage[]
  onDraftChange: (value: string) => void
  onSend: () => void
  onOpenMerchant?: (name: string) => void
  onBuildPuzzlePlan?: (poiIds: string[]) => void
  onBuildAdjustedPuzzlePlan?: (poiIds: string[], adjustmentText: string) => void
}

export function PlanPalChatColumn({
  draft,
  isDisabled = false,
  messages,
  onDraftChange,
  onSend,
  onOpenMerchant,
  onBuildPuzzlePlan,
  onBuildAdjustedPuzzlePlan,
}: PlanPalChatColumnProps) {

  // 为每个消息气泡管理独立的微调输入框状态，防止多轮对话冲突
  const [tweaks, setTweaks] = useState<Record<string, string>>({})
  const [clarifyTime, setClarifyTime] = useState<Record<string, string>>({})
  const [clarifyCount, setClarifyCount] = useState<Record<string, number>>({})
  const [clarifyCustom, setClarifyCustom] = useState<Record<string, string>>({})

  const poiTagRegex = /[\[【]POI[:：]([^:：\]】]+)[:：]([^\]】]+)[\]】]/gi
  const poiInlineRegex = /(\*\*.*?\*\*|[\[【]POI[:：][^:：\]】]+[:：][^\]】]+[\]】])/gi

  function extractPoiIds(content: string) {
    return Array.from(content.matchAll(poiTagRegex)).map((match) => match[1].trim())
  }

  // 解析并渲染包含特殊商家标签 [POI:id:name] 和 Markdown 语法的富文本
  function parseAndRenderContent(content: string) {
    if (!content) return '';

    // 解析单行内的加粗与 POI 标签
    function parseInline(text: string): React.ReactNode[] {
      const parts = text.split(poiInlineRegex);
      return parts.map((part, index) => {
        if (part.startsWith('**') && part.endsWith('**')) {
          const boldText = part.slice(2, -2);
          return <strong key={index} className="font-extrabold text-[#794f27]">{boldText}</strong>;
        }
        if (/^[\[【]poi/i.test(part) && /[\]】]$/.test(part)) {
          const poiMatch = /[\[【]POI[:：]([^:：\]】]+)[:：]([^\]】]+)[\]】]/i.exec(part);
          if (poiMatch) {
            const poiId = poiMatch[1].trim();
            const poiName = poiMatch[2].trim();
            return (
              <span
                key={`${poiId}-${index}`}
                className="inline-flex items-center gap-1 mx-1 px-2.5 py-1 rounded-[12px] border border-[#c4b89e] bg-[#fffdf5] text-[#794f27] text-xs font-black shadow-[0_2px_0_0_#d4c9b4] hover:bg-[#ffeea0] active:translate-y-[1px] active:shadow-none transition-all cursor-pointer select-none"
                onClick={() => onOpenMerchant?.(poiName)}
                title={`点击查看 ${poiName} 详情`}
              >
                🍃 {poiName}
              </span>
            );
          }
        }
        return part;
      });
    }

    const lines = content.split('\n');
    const elements: React.ReactNode[] = [];
    let currentList: React.ReactNode[] = [];

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();

      // 水平分割线
      if (line === '---' || line === '***') {
        if (currentList.length > 0) {
          elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>);
          currentList = [];
        }
        elements.push(<hr key={`hr-${i}`} className="my-3.5 border-t-2 border-[#c4b89e]/30" />);
        continue;
      }

      // H3 标题
      if (line.startsWith('### ')) {
        if (currentList.length > 0) {
          elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>);
          currentList = [];
        }
        const headerText = line.substring(4).trim();
        elements.push(
          <h3 key={`h3-${i}`} className="text-[#794f27] text-base font-black mt-4 mb-2 flex items-center gap-1.5">
            {parseInline(headerText)}
          </h3>
        );
        continue;
      }

      // H4 标题
      if (line.startsWith('#### ')) {
        if (currentList.length > 0) {
          elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>);
          currentList = [];
        }
        const headerText = line.substring(5).trim();
        elements.push(
          <h4 key={`h4-${i}`} className="text-[#794f27] text-sm font-black mt-3 mb-1.5">
            {parseInline(headerText)}
          </h4>
        );
        continue;
      }

      // 列表项
      if (line.startsWith('- ') || line.startsWith('* ') || line.startsWith('• ')) {
        const itemText = line.substring(2).trim();
        currentList.push(
          <li key={`li-${i}-${currentList.length}`} className="text-sm font-bold leading-relaxed text-[#725d42]">
            {parseInline(itemText)}
          </li>
        );
        continue;
      }

      // 空白行
      if (line === '') {
        if (currentList.length > 0) {
          elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>);
          currentList = [];
        }
        elements.push(<div key={`space-${i}`} className="h-2" />);
        continue;
      }

      // 普通段落
      if (currentList.length > 0) {
        elements.push(<ul key={`list-${i}`} className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>);
        currentList = [];
      }

      elements.push(
        <p key={`p-${i}`} className="my-1.5 text-sm font-bold leading-relaxed text-[#725d42]">
          {parseInline(lines[i])}
        </p>
      );
    }

    if (currentList.length > 0) {
      elements.push(<ul key="list-end" className="list-disc pl-5 my-2 space-y-1.5">{...currentList}</ul>);
    }

    return <div className="space-y-0.5">{elements}</div>;
  }

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
              比如“吃饭太远，换个近一点的烧烤”或“我想去哪儿约会，求推荐安静清吧”。我会快速为您探索，或直接定制出行方案。
            </p>
          </Card>
        )}

        {messages.map((message) => {
          const isPlanPal = message.role === 'planpal'
          const hasCta = isPlanPal && message.content.includes('我可以为你构建完整的拼图方案')

          return (
            <div
              key={message.id}
              className={`flex flex-col ${message.role === 'user' ? 'items-end' : 'items-start'}`}
            >
              <div
                className={`max-w-[95%] rounded-[22px] border-2 px-4 py-3 text-sm font-bold leading-relaxed shadow-[0_3px_0_0_#d4c9b4] ${
                  message.role === 'user'
                    ? 'border-[#82d5bb] bg-[#e6f9f6] text-[#0f4c46]'
                    : 'border-[#c4b89e] bg-[#fff9e8] text-[#725d42]'
                }`}
              >
                {isPlanPal ? parseAndRenderContent(message.content) : message.content}

                {/* 交互式补全卡片：当需要用户补充时间/人数要素时展现 */}
                {isPlanPal && message.content.includes('请问您计划在**什么时间段**出行') && (
                  <div className="mt-3.5 pt-3.5 border-t-2 border-[#c4b89e]/30 flex flex-col gap-3.5 bg-[#fcfaf2]/90 border-2 border-[#c4b89e]/60 rounded-[18px] p-4 shadow-inner">
                    <span className="text-[11px] font-black text-[#725d42]/70 uppercase tracking-wider">
                      ⏰ 请选择或填写您的出行细节 (Clarify Details)
                    </span>

                    {/* 时间段选项 */}
                    <div className="flex flex-col gap-1.5">
                      <span className="text-xs font-black text-[#794f27]">1. 出行时间段</span>
                      <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                        {[
                          { label: '🌅 下午 14:00 - 18:00', value: '下午 14:00 到 18:00' },
                          { label: '☀️ 上午 09:00 - 13:00', value: '上午 09:00 到 13:00' },
                          { label: '🌃 晚上 18:00 - 22:00', value: '晚上 18:00 到 22:00' },
                        ].map((opt) => {
                          const selectedValue = clarifyTime[message.id] || '下午 14:00 到 18:00';
                          const isSelected = selectedValue === opt.value;
                          return (
                            <button
                              key={opt.value}
                              type="button"
                              className={`px-3 py-2 text-xs font-bold rounded-[12px] border-2 cursor-pointer transition-all duration-150 ${
                                isSelected
                                  ? 'border-[#2b6cb0]! bg-[#2b6cb0]! text-white! shadow-[0_2px_0_0_#1a365d]!'
                                  : 'border-[#c4b89e]! bg-white! text-[#725d42]! hover:bg-[#ffeea0]!'
                              }`}
                              onClick={() => setClarifyTime((prev) => ({ ...prev, [message.id]: opt.value }))}
                            >
                              {opt.label}
                            </button>
                          );
                        })}
                      </div>
                    </div>

                    {/* 人数选项 */}
                    <div className="flex flex-col gap-1.5">
                      <span className="text-xs font-black text-[#794f27]">2. 出行人数</span>
                      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                        {[
                          { label: '👤 1人独行', value: 1 },
                          { label: '👥 2人约会', value: 2 },
                          { label: '👨‍👩‍👧 3人亲子', value: 3 },
                          { label: '🍀 4人聚会', value: 4 },
                        ].map((opt) => {
                          const selectedValue = clarifyCount[message.id] || 2;
                          const isSelected = selectedValue === opt.value;
                          return (
                            <button
                              key={opt.value}
                              type="button"
                              className={`px-3 py-2 text-xs font-bold rounded-[12px] border-2 cursor-pointer transition-all duration-150 ${
                                isSelected
                                  ? 'border-[#2b6cb0]! bg-[#2b6cb0]! text-white! shadow-[0_2px_0_0_#1a365d]!'
                                  : 'border-[#c4b89e]! bg-white! text-[#725d42]! hover:bg-[#ffeea0]!'
                              }`}
                              onClick={() => setClarifyCount((prev) => ({ ...prev, [message.id]: opt.value }))}
                            >
                              {opt.label}
                            </button>
                          );
                        })}
                      </div>
                    </div>

                    {/* 自定义补充输入 */}
                    <div className="flex flex-col gap-1.5">
                      <span className="text-xs font-black text-[#794f27]">3. 补充其他要求（选填）</span>
                      <input
                        type="text"
                        disabled={isDisabled}
                        className="w-full px-3.5 py-2 border-2 border-[#c4b89e] rounded-[12px] bg-[#fdfcf7] text-xs font-bold text-[#725d42] placeholder-[#9f927d]/80 outline-none focus:border-[#2b6cb0] transition-colors disabled:opacity-50"
                        placeholder="例如：避开辣的餐厅，或者多安排户外活动..."
                        value={clarifyCustom[message.id] || ''}
                        onChange={(e) => setClarifyCustom((prev) => ({ ...prev, [message.id]: e.target.value }))}
                      />
                    </div>

                    <div className="border-t border-[#c4b89e]/30 my-0.5"></div>

                    {/* 提交一键合成按钮 */}
                    <Button
                      type="primary"
                      className="w-full bg-[#ffcc00]! border-[#ffcc00]! text-[#725d42]! shadow-[0_4px_0_0_#dba90e]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-black text-sm py-2.5! h-auto! rounded-[12px]!"
                      onClick={() => {
                        const time = clarifyTime[message.id] || '下午 14:00 到 18:00';
                        const count = clarifyCount[message.id] || 2;
                        const custom = clarifyCustom[message.id] || '';
                        
                        let combinedPrompt = `我计划在${time}出行，总共 ${count} 个人。`;
                        if (custom.trim()) {
                          combinedPrompt += `特殊要求：${custom}。`;
                        }

                        // 填充到 Draft 并触发发送
                        onDraftChange(combinedPrompt);
                        setTimeout(() => {
                          onSend();
                        }, 50);
                      }}
                    >
                      ✨ 填齐了，一键合成行程拼图 (Submit & Build)
                    </Button>
                  </div>
                )}

                {/* 交互式微调决策卡片：当微调时间超限/容量冲突时展现 */}
                {isPlanPal && message.content.includes('检测到您想在晚上增加喝酒安排') && (
                  <div className="mt-3.5 pt-3.5 border-t-2 border-[#c4b89e]/30 flex flex-col gap-3.5 bg-[#fcfaf2]/90 border-2 border-[#c4b89e]/60 rounded-[18px] p-4 shadow-inner">
                    <span className="text-[11px] font-black text-[#725d42]/70 uppercase tracking-wider">
                      🤔 行程冲突决策方案 (Resolve Trip Conflict)
                    </span>
                    <p className="m-0 text-xs font-semibold text-[#725d42] leading-relaxed">
                      由于当前行程窗口仅限下午 18:00 结束且已被完全排满，请问您倾向于以哪种方式微调？
                    </p>

                    <div className="flex flex-col gap-2.5">
                      <Button
                        type="primary"
                        className="w-full bg-[#2b6cb0]! border-[#2b6cb0]! text-[#fff]! shadow-[0_3px_0_0_#1a365d]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-bold text-xs py-2! h-auto! rounded-[12px]! flex items-center justify-start gap-2 pl-3!"
                        onClick={() => {
                          onDraftChange("帮我顺延行程时间至晚上 21:00，并在后面加上喝酒");
                          setTimeout(() => onSend(), 50);
                        }}
                      >
                        🕒 顺延行程时间：顺延至 21:00 并放在晚上
                      </Button>

                      <Button
                        type="primary"
                        className="w-full bg-[#2b6cb0]! border-[#2b6cb0]! text-[#fff]! shadow-[0_3px_0_0_#1a365d]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-bold text-xs py-2! h-auto! rounded-[12px]! flex items-center justify-start gap-2 pl-3!"
                        onClick={() => {
                          onDraftChange("保持 18:00 结束，去掉下午的城市观景台活动，换成去喝酒");
                          setTimeout(() => onSend(), 50);
                        }}
                      >
                        🔄 替换下午活动：去掉“城市观景台”，替换为晚上喝酒
                      </Button>

                      <Button
                        type="primary"
                        className="w-full bg-[#2b6cb0]! border-[#2b6cb0]! text-[#fff]! shadow-[0_3px_0_0_#1a365d]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-bold text-xs py-2! h-auto! rounded-[12px]! flex items-center justify-start gap-2 pl-3!"
                        onClick={() => {
                          onDraftChange("保持 18:00 结束，去掉下午茶小橘子果汁咖啡，换成去喝酒");
                          setTimeout(() => onSend(), 50);
                        }}
                      >
                        🧁 替换下午茶：去掉“小橘子果汁咖啡”，替换为晚上喝酒
                      </Button>
                    </div>

                    <div className="border-t border-[#c4b89e]/30 my-0.5"></div>

                    {/* 自定义微调输入 */}
                    <div className="flex flex-col gap-1.5">
                      <span className="text-xs font-black text-[#794f27]">✍️ 自定义微调要求（选填）</span>
                      <div className="flex gap-2">
                        <input
                          type="text"
                          disabled={isDisabled}
                          className="flex-1 px-3.5 py-2 border-2 border-[#c4b89e] rounded-[12px] bg-[#fdfcf7] text-xs font-bold text-[#725d42] placeholder-[#9f927d]/80 outline-none focus:border-[#2b6cb0] transition-colors disabled:opacity-50"
                          placeholder="手动输入您的调整要求，如：下午3点开始..."
                          value={tweaks[message.id] || ''}
                          onChange={(e) => setTweaks((prev) => ({ ...prev, [message.id]: e.target.value }))}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                              const custom = tweaks[message.id] || '';
                              if (custom.trim()) {
                                onDraftChange(custom);
                                setTimeout(() => {
                                  onSend();
                                  setTweaks((prev) => ({ ...prev, [message.id]: '' }));
                                }, 50);
                              }
                            }
                          }}
                        />
                        <button
                          type="button"
                          disabled={isDisabled || !(tweaks[message.id] || '').trim()}
                          className="px-4 py-2 border-2 border-[#2b6cb0] rounded-[12px] bg-[#2b6cb0] text-xs font-black text-[#fff] hover:scale-[1.02] active:scale-[0.98] active:translate-y-[1px] cursor-pointer transition-all disabled:opacity-50 disabled:pointer-events-none"
                          onClick={() => {
                            const custom = tweaks[message.id] || '';
                            if (custom.trim()) {
                              onDraftChange(custom);
                              setTimeout(() => {
                                onSend();
                                setTweaks((prev) => ({ ...prev, [message.id]: '' }));
                              }, 50);
                            }
                          }}
                        >
                          微调
                        </button>
                      </div>
                    </div>
                  </div>
                )}

                {/* Coding-Agent 级 “同意并构建 / 微调输入” 组合卡片 */}
                {hasCta && (
                  <div className="mt-3.5 pt-3.5 border-t-2 border-[#c4b89e]/30 flex flex-col gap-2.5 bg-[#fcfaf2]/80 border-2 border-[#c4b89e]/60 rounded-[16px] p-3 shadow-inner">
                    <div className="flex items-center justify-between">
                      <span className="text-[11px] font-black text-[#725d42]/70 uppercase tracking-wider">
                        📋 行程拼图一键合成申请 (Action Request)
                      </span>
                    </div>

                    {/* 上部动作按钮：同意构建 */}
                    <Button
                      type="primary"
                      className="w-full bg-[#2b6cb0]! border-[#2b6cb0]! text-[#fff]! shadow-[0_4px_0_0_#1a365d]! hover:scale-[1.01] active:scale-[0.99] active:translate-y-[1px] active:shadow-none transition-all duration-150 font-black text-sm py-2.5! h-auto! rounded-[12px]! flex justify-center items-center gap-1.5"
                      onClick={() => {
                        const matchedPoiIds = extractPoiIds(message.content)

                        if (onBuildPuzzlePlan && matchedPoiIds.length > 0) {
                          onBuildPuzzlePlan(matchedPoiIds)
                        }
                      }}
                    >
                      🎨 同意并构建行程 (Accept & Build)
                    </Button>

                    <div className="border-t border-[#c4b89e]/30 my-0.5"></div>

                    {/* 下部微调输入栏 */}
                    <div className="flex gap-2">
                      <input
                        type="text"
                        disabled={isDisabled}
                        className="flex-1 px-3.5 py-2 border-2 border-[#c4b89e] rounded-[12px] bg-[#fdfcf7] text-xs font-bold text-[#725d42] placeholder-[#9f927d]/80 outline-none focus:border-[#2b6cb0] transition-colors disabled:opacity-50"
                        placeholder="输入微调要求，如：换成下午5点开始，或换个地方..."
                        value={tweaks[message.id] || ''}
                        onChange={(e) => setTweaks((prev) => ({ ...prev, [message.id]: e.target.value }))}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            const matchedPoiIds = extractPoiIds(message.content)
                            const adjustment = tweaks[message.id] || ''
                            if (adjustment.trim() && onBuildAdjustedPuzzlePlan && matchedPoiIds.length > 0) {
                              onBuildAdjustedPuzzlePlan(matchedPoiIds, adjustment)
                              setTweaks((prev) => ({ ...prev, [message.id]: '' }))
                            }
                          }
                        }}
                      />
                      <button
                        type="button"
                        disabled={isDisabled || !(tweaks[message.id] || '').trim()}
                        className="px-4 py-2 border-2 border-[#2b6cb0] rounded-[12px] bg-[#2b6cb0] text-xs font-black text-[#fff] hover:scale-[1.02] active:scale-[0.98] active:translate-y-[1px] cursor-pointer transition-all disabled:opacity-50 disabled:pointer-events-none"
                        onClick={() => {
                          const matchedPoiIds = extractPoiIds(message.content)
                          const adjustment = tweaks[message.id] || ''
                          if (adjustment.trim() && onBuildAdjustedPuzzlePlan && matchedPoiIds.length > 0) {
                            onBuildAdjustedPuzzlePlan(matchedPoiIds, adjustment)
                            setTweaks((prev) => ({ ...prev, [message.id]: '' }))
                          }
                        }}
                      >
                        微调
                      </button>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )
        })}
      </div>

      <div className="border-t-2 border-[#c4b89e]/60 bg-[#fff9e8] p-3">
        <div className="grid grid-cols-[1fr_auto] gap-2">
          <Input
            allowClear
            value={draft}
            disabled={isDisabled}
            placeholder="告诉 PlanPal 想去哪或怎么改..."
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
