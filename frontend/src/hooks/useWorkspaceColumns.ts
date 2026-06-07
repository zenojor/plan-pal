import { useEffect, useRef, useState } from 'react'
import { columnMeta } from '../data/planData'
import type { ColumnId, MobileTabId } from '../types/plan'

export function useWorkspaceColumns(onMerchantSelected: (place: string) => void) {
  const [columns, setColumns] = useState<ColumnId[]>(['chat', 'puzzle'])
  const [draggingColumn, setDraggingColumn] = useState<ColumnId | null>(null)
  const [dragOverColumn, setDragOverColumn] = useState<ColumnId | null>(null)
  const [isColumnMenuOpen, setIsColumnMenuOpen] = useState(false)
  const [activeMobileTab, setActiveMobileTab] = useState<MobileTabId>('puzzle')
  const columnContainerRef = useRef<HTMLDivElement>(null)

  const closedColumns = (Object.keys(columnMeta) as ColumnId[]).filter(
    (column) => column !== 'puzzle' && !columns.includes(column),
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

  function resetPlanningColumns() {
    setColumns((current) => current.includes('dev') ? ['chat', 'puzzle', 'dev'] : ['chat', 'puzzle'])
    setActiveMobileTab('puzzle')
  }

  function addColumn(columnId: ColumnId) {
    setColumns((current) => (current.includes(columnId) ? current : [...current, columnId]))
    setIsColumnMenuOpen(false)
  }

  function openMerchantColumn(place: string) {
    onMerchantSelected(place)
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

  return {
    activeMobileTab,
    addColumn,
    closedColumns,
    columnContainerRef,
    columns,
    dragOverColumn,
    draggingColumn,
    handleColumnDrop,
    isColumnMenuOpen,
    openMerchantColumn,
    removeColumn,
    resetPlanningColumns,
    setActiveMobileTab,
    setDragOverColumn,
    setDraggingColumn,
    setIsColumnMenuOpen,
  }
}
