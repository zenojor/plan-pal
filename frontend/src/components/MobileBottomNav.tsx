import type { ReactNode } from 'react'
import type { MobileTabId } from '../types/plan'

type MobileBottomNavProps = {
  activeMobileTab: MobileTabId
  onTabChange: (column: MobileTabId) => void
}

type MobileNavItem = {
  id: MobileTabId
  label: string
  icon: ReactNode
}

const mobileNavItems: MobileNavItem[] = [
  {
    id: 'puzzle',
    label: '拼图',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
        <path d="M9 11l3 3L22 4" />
        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
      </svg>
    ),
  },
  {
    id: 'merchant',
    label: '商家',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
        <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
        <polyline points="9 22 9 12 15 12 15 22" />
      </svg>
    ),
  },
  {
    id: 'details',
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
    id: 'map',
    label: '路线',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="w-[18px] h-[18px]">
        <polygon points="3 6 9 3 15 6 21 3 21 18 15 21 9 18 3 21" />
        <line x1="9" y1="3" x2="9" y2="18" />
        <line x1="15" y1="6" x2="15" y2="21" />
      </svg>
    ),
  },
]

export function MobileBottomNav({
  activeMobileTab,
  onTabChange,
}: MobileBottomNavProps) {
  return (
    <nav className="fixed bottom-0 inset-x-0 z-40 md:hidden bg-[#fff9e8]/96 backdrop-blur-md border-t-2 border-[rgba(196,184,158,0.38)] px-4 py-3 flex items-center justify-around shadow-[0_-4px_16px_rgba(61,52,40,0.05)] pb-safe-bottom">
      {mobileNavItems.map((item) => {
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
            onClick={() => onTabChange(item.id)}
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
  )
}
