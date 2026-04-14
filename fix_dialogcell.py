import sys

file_path = "TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"
with open(file_path, 'r') as f:
    lines = f.readlines()

new_lines = []
skip_next_brace = False
for i, line in enumerate(lines):
    # Line numbers in view_file are 1-indexed.
    # We want to remove the brace at line 3453 and 3544.
    if i + 1 == 3453:
        if "}" in line:
            print(f"Removing stray brace at line 3453: {line.strip()}")
            continue
    if i + 1 == 3544:
        if "}" in line:
            print(f"Removing stray brace at line 3544: {line.strip()}")
            continue
    new_lines.append(line)

with open(file_path, 'w') as f:
    f.writelines(new_lines)

print("Fix applied.")
