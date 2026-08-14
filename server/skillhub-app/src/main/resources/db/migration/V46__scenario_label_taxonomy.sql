-- Replace the capability taxonomy with skillhub.cn-style scenario categories.
--
-- The old 8 labels (learning / writing / productivity / visual-design / development /
-- utility / ai-literacy / meta) are dropped; label_translation and skill_label cascade on
-- delete, so every skill loses its tags here and is re-tagged by the LLM auto-tagging
-- backfill (see LabelBackfillRunner).

DELETE FROM label_definition
 WHERE slug IN ('learning', 'writing', 'productivity', 'visual-design', 'development', 'utility', 'ai-literacy', 'meta');

INSERT INTO label_definition (slug, type, visible_in_filter, sort_order, created_by) VALUES
  ('ai-intelligence',     'RECOMMENDED', TRUE, 0, NULL),
  ('development-tools',   'RECOMMENDED', TRUE, 1, NULL),
  ('productivity',        'RECOMMENDED', TRUE, 2, NULL),
  ('data-analysis',       'RECOMMENDED', TRUE, 3, NULL),
  ('content-creation',    'RECOMMENDED', TRUE, 4, NULL),
  ('security-compliance', 'RECOMMENDED', TRUE, 5, NULL),
  ('collaboration',       'RECOMMENDED', TRUE, 6, NULL);

INSERT INTO label_translation (label_id, locale, display_name)
SELECT d.id, t.locale, t.display_name
FROM label_definition d
JOIN (VALUES
  ('ai-intelligence',     'zh', 'AI 智能'),
  ('ai-intelligence',     'en', 'AI Intelligence'),
  ('development-tools',   'zh', '开发工具'),
  ('development-tools',   'en', 'Development Tools'),
  ('productivity',        'zh', '效率提升'),
  ('productivity',        'en', 'Productivity'),
  ('data-analysis',       'zh', '数据分析'),
  ('data-analysis',       'en', 'Data Analysis'),
  ('content-creation',    'zh', '内容创作'),
  ('content-creation',    'en', 'Content Creation'),
  ('security-compliance', 'zh', '安全合规'),
  ('security-compliance', 'en', 'Security & Compliance'),
  ('collaboration',       'zh', '通讯协作'),
  ('collaboration',       'en', 'Collaboration')
) AS t(slug, locale, display_name)
ON d.slug = t.slug;
