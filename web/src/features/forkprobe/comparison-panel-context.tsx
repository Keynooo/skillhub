'use client'

import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'

/**
 * Skill preselection payload — passed when opening the comparison panel from
 * a specific skill's detail page.
 */
export interface PreselectedSkill {
  coordinate: string // "namespace/slug"
  name: string
  namespace: string
}

interface ComparisonPanelState {
  /** Whether the slide-out panel is visible. */
  isOpen: boolean
  /** Skill to preselect when the panel opens, if any. */
  preselectedSkill: PreselectedSkill | null
  openPanel: (skill?: PreselectedSkill) => void
  closePanel: () => void
}

const ComparisonPanelContext = createContext<ComparisonPanelState | null>(null)

/**
 * Provider that wraps the application layout so any page can open the
 * comparison panel without prop drilling.
 */
export function ComparisonPanelProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false)
  const [preselectedSkill, setPreselectedSkill] = useState<PreselectedSkill | null>(null)

  const openPanel = useCallback((skill?: PreselectedSkill) => {
    setPreselectedSkill(skill ?? null)
    setIsOpen(true)
  }, [])

  const closePanel = useCallback(() => {
    setIsOpen(false)
    // Keep preselectedSkill briefly so the exit animation can play;
    // reset it on the next openPanel call or after a short delay.
  }, [])

  return (
    <ComparisonPanelContext.Provider value={{ isOpen, preselectedSkill, openPanel, closePanel }}>
      {children}
    </ComparisonPanelContext.Provider>
  )
}

/**
 * Hook to open/close the comparison panel from any component inside the provider.
 */
export function useComparisonPanel() {
  const ctx = useContext(ComparisonPanelContext)
  if (!ctx) {
    throw new Error('useComparisonPanel must be used within a ComparisonPanelProvider')
  }
  return ctx
}
