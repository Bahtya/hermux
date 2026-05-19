# 构建 pre-built venv

Hermux 的 Python Agent 运行在 pre-built venv 中，打包为 `venv-aarch64.tar.gz` 并上传到 hermux release。

## 构建环境

- 真机 Termux ARM64 设备（不能交叉编译，psutil 等需要 native 编译）
- SSH 可访问
- 依赖：`python rust make clang pkg-config libffi openssl ca-certificates git`

## 构建脚本

```
scripts/build-venv-device.py
```

通过 SSH 连接到 Termux 设备，自动完成：clone hermes-agent → 创建 venv → 安装依赖 → 路径修复 → 打包下载。

```bash
pip install paramiko
python scripts/build-venv-device.py \
    --host 127.0.0.1 --port 8022 \
    --user u0_a401 --password <password> \
    --hermes-commit <commit-hash> \
    --output venv-aarch64.tar.gz
```

ADB 端口转发（如需）：
```bash
adb forward tcp:8022 tcp:8022
```

## 构建步骤

| 步骤 | 说明 |
|------|------|
| 1 | 重装 python/ndk-sysroot/libffi（修复 Termux 缺失头文件） |
| 2 | clone hermes-agent 到指定 commit |
| 3 | `python -m venv` + 升级 pip/setuptools/wheel |
| 4 | 编译 psutil（使用官方 Android patch） |
| 5 | 安装 hermes-agent[termux-all] + feishu + pycryptodome |
| 6 | 验证所有关键 import |
| 7 | 写入 `.hermes-build-info` 元数据 |
| 8 | **路径修复** com.termux → com.hermux |
| 8b | **权限归一化** |
| 9 | `tar czf --owner=0 --group=0` 打包 + SFTP 下载 |

## 路径修复（关键）

venv 在 Termux 上构建，所有路径为 `/data/data/com.termux/`。Hermux 的包名是 `com.hermux`，所以必须在打包前修复：

### 需要修复的内容

1. **符号链接**：`venv/bin/python` → `/data/data/com.termux/files/usr/bin/python`
   - 修复后 → `/data/data/com.hermux/files/usr/bin/python`
2. **shebang**：所有 `venv/bin/*` 脚本头部的 `#!/data/data/com.termux/.../python`
3. **pyvenv.cfg**：`home`、`executable`、`command` 字段
4. **.pth/.egg-link 文件**：如果有的话

### 脚本自动处理

Step 8 会自动扫描并替换：
- `find -type l` 修复所有指向 `com.termux` 的 symlink
- `find -type f` + `grep` + `sed` 替换文本文件中的路径（跳过 .pyc/.so 等二进制）

## 权限和归属

### 打包时

```
目录: 755
普通文件: 644
bin/* 脚本: 755
tar --owner=0 --group=0 --numeric-owner
```

`--owner=0 --group=0` 让 tar 元数据归属 root:root。在 Android 上提取时，非 root 进程无法修改归属，文件自动归提取进程的 UID（即 hermux 应用的用户），避免带入构建设备的 `u0_a401` 归属。

### 提取时

`VenvExtractor.java` 执行 `tar xzf` + `chmod 755 bin/*`。不需要再做路径修复或权限调整。

## 上传 Release

```bash
# 确认 gradle.properties 中的版本号
grep hermesVenvVersion gradle.properties

# 上传到对应 tag 的 release
gh release upload <tag> venv-aarch64.tar.gz --clobber
```

`build.gradle` 的 `downloadVenvs` task 从这个 release 下载 venv 到 APK assets：
```groovy
hermesVenvVersion=v0.13.1  // gradle.properties
// 下载 URL: https://github.com/Bahtya/hermux/releases/download/<tag>/venv-aarch64.tar.gz
```

## 版本规则

Release tag 跟随 hermes-agent 版本（如 `v0.13.1`），不是 hermux APK 版本。

更新 venv 时：
1. 用新 commit hash 运行 build 脚本
2. 创建新 tag（或复用旧 tag 并 `--clobber` 替换资源）
3. 更新 `gradle.properties` 中的 `hermesVenvVersion`

## 注意事项

- **不能交叉编译**：psutil 和其他 C 扩展需要 ARM64 native 编译
- **watchfiles 要卸载**：其 C 扩展在 Android ARM64 上会 SIGABRT
- **websockets 版本**：lark-oapi 要求 `<16`，需要手动降级
- **pycryptodome**：Termux 上需要 `CC=clang`
- **jiter**：经常在批量安装中失败，需单独安装重试
- **venv 体积**：~70MB 压缩后，解压 ~300MB。手机端解压需要几分钟
- **下载是非致命的**：`build.gradle` 中 venv 下载失败不阻断 APK 构建（本地开发/P PR 不需要）
