#!/usr/bin/env python3
"""Seed SkillHub 平台的分类标签（Label）并给内置 skills 打标。

用法（本地 dev，mock auth）:
    python scripts/seed-labels.py            # 默认 http://localhost:8080
    BASE_URL=http://localhost:8080 python scripts/seed-labels.py

要求后端以 local profile 运行（skillhub.auth.mock.enabled=true），
脚本用 X-Mock-User-Id: local-admin 模拟管理员。

幂等：先删除现有标签定义，再重建，可重复执行。
"""
import os
import sys

import requests

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")

# (slug, 英文名, 中文名, 排序)
LABELS = [
    ("learning", "Learning", "学习", 0),
    ("writing", "Writing", "写作", 1),
    ("productivity", "Productivity", "效率", 2),
    ("visual-design", "Visual Design", "可视化", 3),
    ("development", "Development", "开发", 4),
    ("utility", "Utility", "工具", 5),
    ("ai-literacy", "AI Literacy", "AI 素养", 6),
    ("meta", "Platform Tool", "平台工具", 7),
]

# 内置 skill slug -> 标签 slug 列表（全部在 global namespace）
SKILL_LABELS = {
    "skillhub-hello": ["meta"],
    "agentguard": ["ai-literacy"],
    "ai-claim-checker": ["ai-literacy"],
    "daily-standup-journal": ["productivity"],
    "decision-matrix": ["productivity"],
    "diagram-maker": ["visual-design"],
    "documentation-writer": ["writing"],
    "exam-ready": ["learning"],
    "forkprobe": ["meta"],
    "frontend-design": ["visual-design", "development"],
    "linkedin-post-formatter": ["writing"],
    "meeting-note-summarizer": ["writing", "productivity"],
    "retrieval-practice-generator": ["learning"],
    "storytelling-advisor": ["writing"],
    "study-strategy-selector": ["learning"],
    "time-blocking-scheduler": ["productivity"],
    "video-frames": ["development", "utility"],
    "weather": ["utility"],
}


def main() -> int:
    session = requests.Session()
    session.headers["X-Mock-User-Id"] = "local-admin"

    def csrf_headers(extra=None):
        headers = dict(extra or {})
        token = session.cookies.get("XSRF-TOKEN")
        if token:
            headers["X-XSRF-TOKEN"] = token
        return headers

    def check(resp, what):
        if not resp.ok:
            print(f"  !! {what} -> HTTP {resp.status_code}: {resp.text[:200]}")
            return False
        return True

    # 1. 触发 CSRF cookie 下发
    session.get(f"{BASE}/api/v1/auth/providers")

    # 2. 清空现有标签定义
    existing = session.get(f"{BASE}/api/v1/admin/labels").json()["data"]
    for definition in existing:
        slug = definition["slug"]
        resp = session.delete(
            f"{BASE}/api/v1/admin/labels/{slug}", headers=csrf_headers()
        )
        if check(resp, f"delete label {slug}"):
            print(f"  删除旧标签 {slug}")

    # 3. 重建标签
    for slug, en, zh, order in LABELS:
        body = {
            "slug": slug,
            "type": "RECOMMENDED",
            "visibleInFilter": True,
            "sortOrder": order,
            "translations": [
                {"locale": "en", "displayName": en},
                {"locale": "zh", "displayName": zh},
            ],
        }
        resp = session.post(
            f"{BASE}/api/v1/admin/labels",
            headers=csrf_headers({"Content-Type": "application/json"}),
            json=body,
        )
        if check(resp, f"create label {slug}"):
            print(f"  创建标签 {slug} = {en} / {zh}")

    # 4. 给内置 skills 打标
    for skill_slug, label_slugs in SKILL_LABELS.items():
        for label_slug in label_slugs:
            resp = session.put(
                f"{BASE}/api/v1/skills/global/{skill_slug}/labels/{label_slug}",
                headers=csrf_headers(),
            )
            if check(resp, f"attach {label_slug} -> {skill_slug}"):
                print(f"  打标 {skill_slug} += {label_slug}")

    # 5. 汇总
    visible = session.get(f"{BASE}/api/v1/labels").json()["data"]
    print(f"\n完成：{len(LABELS)} 个标签定义，{len(SKILL_LABELS)} 个 skill 已打标，可见标签 {len(visible)} 个")
    return 0


if __name__ == "__main__":
    sys.exit(main())
