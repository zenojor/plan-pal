import { useState } from 'react'
import type { ChatMessage } from '../types/plan'

export function useChatMessages() {
  const [chatDraft, setChatDraft] = useState('')
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([])

  return {
    chatDraft,
    setChatDraft,
    chatMessages,
    setChatMessages,
  }
}
