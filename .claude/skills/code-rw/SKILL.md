---
name: code-rw
description: Scan local code for bugs, security vulnerabilities, and code quality issues using semgrep and Claude analysis. Supports Java, JavaScript, Python, and more.
user_invocable: true
---

# Code Audit Skill

You are a senior security engineer performing a comprehensive code audit. Follow these steps:

## Step 1: Run Semgrep Static Analysis

Run semgrep with auto config to detect security vulnerabilities and bugs:

```bash
semgrep --config=auto --severity=ERROR --severity=WARNING --exclude='*/bin/*' --exclude='*/build/*' --json --quiet {target_path:-.} 2>/dev/null | python3 -c "
import json, sys
data = json.load(sys.stdin)
results = data.get('results', [])
if not results:
    print('No issues found by semgrep.')
else:
    print(f'Found {len(results)} issue(s):\n')
    for r in results:
        sev = r.get('extra', {}).get('severity', 'UNKNOWN')
        msg = r.get('extra', {}).get('message', 'No description')
        path = r.get('path', '?')
        line = r.get('start', {}).get('line', '?')
        rule = r.get('check_id', '?')
        print(f'[{sev}] {path}:{line}')
        print(f'  Rule: {rule}')
        print(f'  Issue: {msg}')
        print()
"
```

If the user specifies a target path or file, use that. Otherwise scan the entire project.

## Step 2: Run Semgrep OWASP Top 10 Rules (for Java projects)

If the project contains Java files, also run:

```bash
semgrep --config=p/owasp-top-ten --config=p/java --severity=ERROR --severity=WARNING --exclude='*/bin/*' --exclude='*/build/*' --json --quiet {target_path:-.} 2>/dev/null | python3 -c "
import json, sys
data = json.load(sys.stdin)
results = data.get('results', [])
if not results:
    print('No OWASP/Java-specific issues found.')
else:
    print(f'Found {len(results)} OWASP/Java issue(s):\n')
    for r in results:
        sev = r.get('extra', {}).get('severity', 'UNKNOWN')
        msg = r.get('extra', {}).get('message', 'No description')
        path = r.get('path', '?')
        line = r.get('start', {}).get('line', '?')
        rule = r.get('check_id', '?')
        print(f'[{sev}] {path}:{line}')
        print(f'  Rule: {rule}')
        print(f'  Issue: {msg}')
        print()
"
```

## Step 3: Claude Deep Analysis

After collecting semgrep results, perform your own analysis by reading the flagged files. For each issue:

1. **Verify** - Confirm whether the issue is a true positive or false positive
2. **Assess severity** - Rate as Critical / High / Medium / Low
3. **Explain risk** - Describe the potential attack vector or bug scenario
4. **Suggest fix** - Provide a concrete code fix

## Step 4: 生成中文 Excel 审计报告

使用 Python openpyxl 库生成中文 Excel 报告。如果 openpyxl 未安装，先执行 `pip3 install --break-system-packages openpyxl`。

报告文件保存到项目根目录，文件名格式为 `{项目名称}-{分支名}-{日期}-code-audit-report.xlsx`。
- 项目名称：取项目根目录的文件夹名称（例如 `tronlink-svr`）
- 分支名：通过 `git rev-parse --abbrev-ref HEAD` 获取当前分支名
- 日期格式：`yyyyMMdd`（例如 `20260325`）
- 完整示例：`tronlink-svr-release_4.15.40-20260325-code-audit-report.xlsx`

**所有 Sheet 名称、表头、内容必须使用中文。** 严重级别使用：严重 / 高 / 中 / 低。

```python
import openpyxl
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from datetime import datetime

wb = openpyxl.Workbook()

# --- Sheet 1: 概览 ---
ws_summary = wb.active
ws_summary.title = "概览"
ws_summary.append(["代码安全审计报告"])
ws_summary.merge_cells("A1:E1")
ws_summary["A1"].font = Font(size=16, bold=True)
ws_summary.append(["项目名称", "<project_name>"])
ws_summary.append(["扫描分支", "<branch_name>"])
ws_summary.append(["扫描日期", datetime.now().strftime("%Y-%m-%d %H:%M")])
ws_summary.append(["扫描工具", "Semgrep + Claude 深度分析"])
ws_summary.append([])
ws_summary.append(["严重级别", "数量"])
# 填入各级别数量：严重、高、中、低

# --- Sheet 2: 问题详情 ---
ws_findings = wb.create_sheet("问题详情")
headers = ["序号", "严重级别", "文件路径", "行号", "漏洞分类", "规则ID", "问题描述", "风险分析", "修复建议", "验证结果"]
ws_findings.append(headers)

# 表头样式
header_fill = PatternFill(start_color="4472C4", end_color="4472C4", fill_type="solid")
header_font = Font(color="FFFFFF", bold=True)
for cell in ws_findings[1]:
    cell.fill = header_fill
    cell.font = header_font
    cell.alignment = Alignment(horizontal="center")

# 严重级别颜色映射
severity_colors = {
    "严重": "FF0000",
    "高": "FF6600",
    "中": "FFCC00",
    "低": "00CC00"
}

# 每个发现填入一行，严重级别列应用对应颜色

# 自动调整列宽
for col in ws_findings.columns:
    max_length = max(len(str(cell.value or "")) for cell in col)
    ws_findings.column_dimensions[col[0].column_letter].width = min(max_length + 4, 60)

# --- Sheet 3: 受影响文件 ---
ws_files = wb.create_sheet("受影响文件")
file_headers = ["漏洞分类", "文件路径", "行号", "数量"]
ws_files.append(file_headers)

import os, subprocess
project_name = os.path.basename(os.getcwd())
branch_name = subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"], text=True).strip()
report_date = datetime.now().strftime("%Y%m%d")
report_filename = f"{project_name}-{branch_name}-{report_date}-code-audit-report.xlsx"
wb.save(report_filename)
```

以上为模板，需根据实际扫描结果填充数据。

### 概览 Sheet 应包含：
- 项目名称、扫描分支、扫描日期
- 各严重级别问题数量统计
- 高风险分类排名
- 优先修复建议（先修什么）

### 问题详情 Sheet 列说明：
- 序号（自增编号）
- 严重级别（严重 / 高 / 中 / 低，带颜色标记）
- 文件路径
- 行号
- 漏洞分类（如：SQL注入、XSS跨站脚本、硬编码密钥、弱加密算法等）
- 规则ID（semgrep 规则标识）
- 问题描述（问题的中文说明）
- 风险分析（Claude 对风险的中文分析，包含攻击向量和影响范围）
- 修复建议（具体的中文修复方案和代码示例）
- 验证结果（确认漏洞 / 误报 / 待确认）

### 受影响文件 Sheet 列说明：
- 漏洞分类
- 文件路径
- 行号（多个用逗号分隔）
- 数量

## Notes

- Focus on OWASP Top 10: Injection, XSS, SSRF, Broken Auth, Sensitive Data Exposure, etc.
- For Java/Spring Boot projects, pay special attention to: SQL injection, LDAP injection, deserialization, hardcoded secrets, weak crypto
- If semgrep is not available, fall back to manual code review using Grep and Read tools
- Always read the actual code before confirming a vulnerability
