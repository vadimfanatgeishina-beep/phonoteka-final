#!/usr/bin/env python3
"""Extract real unstable-parameter composables from a supplied compiler report. Never invent counts."""
from pathlib import Path
import re,sys
if len(sys.argv)!=3:raise SystemExit('Usage: python tools/summarize_compose_reports.py <composables.txt> <output.md>')
source=Path(sys.argv[1]);text=source.read_text()
blocks=re.findall(r'(?m)^(?:restartable|skippable|scheme|fun)[^\n]*fun .*?(?=^(?:restartable|skippable|scheme|fun)[^\n]*fun |\Z)',text,re.S|re.M)
if not blocks:blocks=re.split(r'(?m)(?=^(?:restartable|skippable).*fun )',text)
selected=[block.strip() for block in blocks if re.search(r'(?m)^\s*unstable\s+',block)]
output=['# Фактический фрагмент Compose Compiler report','',f'Source: `{source.name}`; данные извлечены, не baseline и не runtime counts.','']
for block in selected[:3]:output.extend(['```text',block,'```',''])
if len(selected)<3:output.append(f'Найдено только {len(selected)} блоков с unstable-параметрами; три функции НЕ выдуманы.')
Path(sys.argv[2]).write_text('\n'.join(output)+'\n');print('Extracted existing report blocks:',min(3,len(selected)))
