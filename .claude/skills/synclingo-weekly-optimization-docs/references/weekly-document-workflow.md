# Weekly Document Workflow

## Canonical Documents

- Optimization history: `docs/optimization-implementation-plan.md`
- Validation history: `docs/optimization-validation-test-plan.md`

Use these two files as the long-lived weekly records for the project.

## Core Rules

1. Add each new week's optimization work to the canonical optimization document instead of creating a new weekly optimization file.
2. After the week's optimization work is completed or updated, add the matching weekly validation section to the canonical validation document.
3. Use one shared week label in both files, for example:
   - `2026-W20`
   - `2026-05-11 to 2026-05-17`
4. Append new sections and preserve historical entries.
5. Keep side documents supplementary. If a temporary or phase-specific plan is useful, summarize the final weekly result back into the canonical documents.

## Optimization Entry Template

```markdown
## 周度优化记录：2026-W20

### 本周目标

- ...

### 优化项

| 优化项 | 状态 | 说明 |
|---|---|---|
| ... | 已完成 / 进行中 / 暂缓 | ... |

### 实施结果

- ...

### 影响范围

- 后端：
- 前端：
- 配置 / 数据：

### 验收标准

- ...

### 遗留问题

- ...
```

## Validation Entry Template

```markdown
## 周度验证记录：2026-W20

### 对应优化范围

- 对应 `docs/optimization-implementation-plan.md` 中的 `周度优化记录：2026-W20`

### 验证目标

- ...

### 优先通过日志 / 接口 / 数据库验证

```powershell
# commands
```

通过标准：

- ...

### 必要时再做人工判断

- ...

### 结果记录

- 通过 / 不通过：
- 遗留问题：
- 需回归项：
```

## Validation Priority

Prefer this order:

1. Backend logs
2. Frontend console logs
3. API responses
4. Database records
5. Manual judgment only when the first four cannot prove the behavior

## Matching Guidance

- Keep titles, week labels, feature names, and acceptance criteria aligned between the two documents.
- If an optimization is deferred, mark it as deferred in the optimization record and exclude it from the week's pass criteria or state why it remains unverified.
- If a validation result exposes a defect, add the defect back to the optimization record as unfinished or follow-up work.
