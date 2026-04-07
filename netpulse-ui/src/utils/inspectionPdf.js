import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'

/**
 * 将 DOM 节点导出为 A4 PDF（多页长图滚动），适合中文界面截图。
 */
export async function downloadElementAsPdf(element, filename = 'export.pdf') {
  if (!element) return
  const canvas = await html2canvas(element, {
    scale: 2,
    useCORS: true,
    logging: false,
    backgroundColor: '#ffffff',
  })
  const imgData = canvas.toDataURL('image/png', 1.0)
  const pdf = new jsPDF({ orientation: 'p', unit: 'mm', format: 'a4' })
  const pageW = pdf.internal.pageSize.getWidth()
  const pageH = pdf.internal.pageSize.getHeight()
  const imgW = pageW
  const imgH = (canvas.height * pageW) / canvas.width
  let heightLeft = imgH
  let position = 0
  pdf.addImage(imgData, 'PNG', 0, position, imgW, imgH)
  heightLeft -= pageH
  while (heightLeft > 0) {
    position = heightLeft - imgH
    pdf.addPage()
    pdf.addImage(imgData, 'PNG', 0, position, imgW, imgH)
    heightLeft -= pageH
  }
  pdf.save(filename)
}
