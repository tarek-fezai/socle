// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
import Table from '@tiptap/extension-table'
import TableCell from '@tiptap/extension-table-cell'
import TableHeader from '@tiptap/extension-table-header'
import TableRow from '@tiptap/extension-table-row'

/**
 * Cellules sans fusion ni largeur de colonne : `colspan` / `rowspan` restent à 1 (le schéma de
 * prosemirror-tables a besoin de ces attributs), jamais lus depuis le HTML ni rendus.
 */
const fixedSpanAttributes = () => ({
  colspan: { default: 1, parseHTML: () => 1, renderHTML: () => ({}) },
  rowspan: { default: 1, parseHTML: () => 1, renderHTML: () => ({}) },
  colwidth: { default: null, parseHTML: () => null, renderHTML: () => ({}) },
})

export const TableNode = Table.configure({ resizable: false })
export const TableRowNode = TableRow
export const TableHeaderNode = TableHeader.extend({ addAttributes: fixedSpanAttributes })
export const TableCellNode = TableCell.extend({ addAttributes: fixedSpanAttributes })

export const tableExtensions = [TableNode, TableRowNode, TableHeaderNode, TableCellNode]
