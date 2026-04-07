-- AI 巡检结论：千问/DeepSeek 根据本次可达性探测结果生成的运维摘要（Markdown）
ALTER TABLE `inspection_report`
  ADD COLUMN `ai_summary` LONGTEXT NULL COMMENT 'AI 巡检分析结论' AFTER `schedule_label`;
