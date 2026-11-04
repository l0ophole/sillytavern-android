#!/bin/bash
export PATH=$PATH:$HOME/Android/Sdk/platform-tools
S=${SHOT_DIR:-/tmp}
dump(){ adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml; }
case $1 in
 tap) dump | python3 -I -c "
import sys,re
x=sys.stdin.read(); t=sys.argv[1]
k=int(sys.argv[2]) if len(sys.argv)>2 else 1
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    a=re.search(r'(?:text|content-desc)=\"([^\"]*)\"',n)
    b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n)
    if a and b and t.lower() in a.group(1).lower():
        k-=1
        if k: continue
        l,tp,r,bt=map(int,b.groups()); print((l+r)//2,(tp+bt)//2); break
else: print('NOTFOUND',file=sys.stderr); sys.exit(1)
" "$2" "${3:-1}" | { read X Y && adb shell input tap $X $Y && echo tapped $2 $X $Y; };;
 texts) dump | grep -o '\(text\|content-desc\)="[^"]\+"' | sort -u | head -${2:-60};;
 shot) adb exec-out screencap -p > $S/$2.png;;
esac
