#!/bin/bash
TOKEN="你的TOKEN"
REPO="qisve/BlurCacheManager"
BRANCH="main"

upload_file() {
    local file=$1
    local content=$(base64 -w 0 "$file")
    local sha=$(curl -s -H "Authorization: token $TOKEN" \
        "https://api.github.com/repos/$REPO/contents/$file?ref=$BRANCH" | grep '"sha"' | head -1 | cut -d'"' -f4)

    if [ -n "$sha" ]; then
        curl -s -X PUT -H "Authorization: token $TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"message\":\"更新 $file\",\"content\":\"$content\",\"sha\":\"$sha\",\"branch\":\"$BRANCH\"}" \
            "https://api.github.com/repos/$REPO/contents/$file" > /dev/null
    else
        curl -s -X PUT -H "Authorization: token $TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"message\":\"添加 $file\",\"content\":\"$content\",\"branch\":\"$BRANCH\"}" \
            "https://api.github.com/repos/$REPO/contents/$file" > /dev/null
    fi
    echo "已上传: $file"
}

for f in $(find . -type f -not -path './.git/*' -not -name 'push.sh' -not -name 'setup.sh' -not -name 'setup.shEnter'); do
    upload_file "$f"
done

echo "=== 全部上传完成 ==="
