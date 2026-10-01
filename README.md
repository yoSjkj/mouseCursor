# mouseCursor

안드로이드 폰에 마우스를 연결했을 때 커서를 숨겨버리는 게임에서, 커서를 대신 그려주고 마우스 클릭을 터치로 바꿔주는 접근성 서비스 앱입니다.

화면(Activity)이 없는 앱이라 런처 아이콘이 없고, 접근성 설정에서 서비스를 켜는 것으로 동작합니다.

## 만든 계기

갤럭시 S26 울트라에 키보드와 마우스를 연결해서 「데이브 더 다이버」 모바일을 하려고 했습니다. 손가락 터치로 하기엔 조작이 너무 힘들었거든요.

그런데 게임에 들어가자마자 마우스 커서가 사라졌습니다. 확인해 보니 상황은 이랬습니다.

- 마우스 클릭은 터치로 잘 먹힌다. WASD 이동도 된다.
- 다만 게임이 커서 아이콘을 숨겨서, 지금 마우스가 어디 있는지 보이지 않는다.
- 게임 설정의 입력 방식은 게임패드(Xbox, 듀얼쇼크4, 듀얼센스, 스위치 프로)만 지원한다.
- 개발자 옵션의 "탭한 항목 표시"는 마우스 클릭에는 반응하지 않는다.
- 밖에서 하는 중이라 패드를 연결할 수 없다.

키매핑 앱을 깔아볼까 하다가, 결국 "커서 위치만 보이면 되는데" 싶어서 직접 만들었습니다. 안드로이드 앱 개발은 처음이었고, PC 없이 폰 하나로 진행했습니다.

## 동작 방식

1. **대상 앱 감지**: 창이 바뀔 때(`TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOWS_CHANGED`)와 1초 주기로, 현재 활성 창의 패키지명을 확인합니다. 알림창(systemui)과 삼성 키보드는 무시해서 모드가 바뀌지 않습니다.
2. **마우스 가져오기**: 대상 앱이 앞에 있으면 `setMotionEventSources(SOURCE_MOUSE)`로 마우스 이벤트를 서비스가 받고, 다른 앱이면 `0`으로 돌려서 시스템 커서와 기본 동작으로 돌아갑니다.
3. **커서 표시**: 마우스 좌표에 링 모양 오버레이(`TYPE_ACCESSIBILITY_OVERLAY`)를 그립니다. 원의 중심이 실제 터치 지점입니다.
   - 반투명(75%) 흰 링 + 가운데 점, 얇은 어두운 테두리 (밝은 배경, 어두운 배경 모두에서 보이도록)
   - 누르고 있는 동안 안쪽이 채워지고 0.8배로 줄어듦
   - 3초 동안 움직임이 없으면 서서히 사라짐
4. **터치 재현**: 버튼을 뗄 때, 누른 위치부터 뗀 위치까지 누른 시간만큼 `dispatchGesture`로 터치를 재현합니다. 이동 거리가 15px 이하면 탭, 넘으면 직선 드래그입니다.

## 개발 환경

전부 폰(갤럭시 S26 울트라, Android 16) 안에서 했습니다.

| 구분 | 사용한 것 |
|---|---|
| IDE / 빌드 | Android Code Studio (AndroidIDE 포크) v1.0.0+gh.r4 |
| SDK / JDK | Android SDK 35.0.1, JDK 17 |
| 빌드 도구 | Gradle 9.0.0, AGP 8.13.0 |
| 앱 설정 | compileSdk 36, minSdk 35, targetSdk 34 |
| 언어 | Java |
| 터미널 | Termux + proot-distro Ubuntu |
| 코드 보조 | Claude (채팅), Claude Code (Ubuntu 안에서 실행) |

### Termux에서 Claude Code 쓰기

Termux에서 npm으로 Claude Code를 설치하면 실행 파일을 받지 못합니다. Termux가 플랫폼을 `linux-arm64-android`로 보고하는데, 배포되는 네이티브 바이너리는 `linux-arm64`까지만 있기 때문입니다. 그래서 Termux 안에 Ubuntu를 띄우고 그 안에서 공식 설치 스크립트를 썼습니다.

```bash
# Termux
termux-change-repo          # Single mirror > default (Cloudflare)
pkg update && pkg upgrade
pkg install proot-distro
termux-setup-storage        # 폰 저장소 접근 권한
proot-distro install ubuntu
proot-distro login ubuntu

# Ubuntu
apt update && apt install -y curl git
curl -fsSL https://claude.ai/install.sh | bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc && source ~/.bashrc
```

Termux의 `~/.bashrc`에 넣어둔 단축 명령입니다.

```bash
alias ub='proot-distro login ubuntu'
alias cl='proot-distro login ubuntu -- bash -lc "cd /sdcard/AndroidIDEProjects && /root/.local/bin/claude"'
```

Termux 미러가 Asia 그룹 무작위로 인도 서버에 잡히면 50kB/s 수준으로 느렸습니다. Single mirror에서 default(Cloudflare)로 고정하니 바로 빨라졌습니다.

## 개발 과정

처음 계획대로 된 건 거의 없었습니다. 순서대로 적으면 이렇습니다.

### 1. 관찰 모드로 시작했다가 실패

처음엔 마우스 이벤트를 가로채지 않고 "관찰만" 해서 좌표를 받아 커서를 그리려고 했습니다. 그러면 클릭은 게임이 원래대로 처리하니까 커서 그림만 얹으면 되거든요. 이를 위해 `AccessibilityServiceInfo.setObservedMotionEventSources()`를 쓰려 했습니다.

- compileSdk에서 메서드를 찾지 못해 컴파일 에러
- 리플렉션으로 실행 시점에 호출하도록 바꿈
- 기기에서 `NoSuchMethodException`. 이 기기의 안드로이드에는 해당 메서드가 없었습니다.

### 2. 가로채기 + 제스처 재현으로 전환

관찰이 안 되니, 마우스를 서비스가 직접 가져오고(`setMotionEventSources`) 클릭은 `dispatchGesture`로 터치를 대신 눌러주는 방식으로 바꿨습니다. 설정에 `canPerformGestures="true"`가 필요합니다. 이 방식으로 처음 커서가 따라오고 게임 클릭이 동작했습니다.

### 3. 게임 밖에서 커서가 두 개

서비스가 항상 마우스를 가져오니, 게임 밖에서는 시스템 커서와 내 커서가 겹쳐 보였습니다. 그래서 대상 게임이 앞에 있을 때만 마우스를 가져오고, 나머지 앱에서는 시스템에 돌려주도록 바꿨습니다. 게임 밖에서는 휠, 우클릭까지 원래대로 됩니다.

### 4. 최근 앱으로 돌아오면 감지 실패

`TYPE_WINDOW_STATE_CHANGED` 이벤트 하나로 앞에 뜬 앱을 판단했더니, 최근 앱 목록에서 게임으로 돌아올 때 이벤트가 오지 않아 모드가 켜지지 않았습니다.

여기서 한참 헤맸습니다. 이 게임은 원래 마우스 클릭을 터치로 받아주기 때문에, **클릭이 된다고 해서 내 모드가 켜진 게 아니었습니다.** 클릭은 되는데 커서만 안 보이니 그림이나 숨김 처리 문제로 착각했었습니다.

해결은 감지를 세 겹으로 한 것입니다.

- `TYPE_WINDOW_STATE_CHANGED` + `TYPE_WINDOWS_CHANGED` 이벤트에서 `getRootInActiveWindow()`로 활성 창의 패키지명 확인
- 1초 주기 확인
- 서비스 연결 시 한 번 확인 (게임 중에 서비스를 껐다 켜도 바로 잡힘)

이를 위해 `canRetrieveWindowContent="true"`와 `flagRetrieveInteractiveWindows`가 필요합니다.

### 5. 로그 보기

로그를 보는 것부터 쉽지 않았습니다.

- ACS의 App Logs(LogWire)는 USB 디버깅이 켜져 있어야 동작
- 화면 없는 앱이라 그런지 토스트도 뜨지 않음

그래서 화면 위에 글자 상자를 오버레이로 띄우는 디버그창을 만들었습니다. 현재 앱, 커서 모드, 감지 경로, 마지막 동작을 보여줍니다. 캡처에도 찍혀서 편했습니다. 코드 맨 위 `DEBUG` 값으로 켜고 끕니다.

### 6. 커서 디자인

처음엔 화살표를 그렸는데, 화살표는 "끝"이 기준점이라 그림 위치를 보정해야 하고 게임 화면에서 튀어 보였습니다. 정확한 위치가 우선이고 어디 있어도 어색하지 않은 게 좋겠다 싶어서 링 + 가운데 점으로 바꿨습니다. 원의 중심이 곧 터치 지점이라 직관적입니다.

## 설치와 사용

1. Android Code Studio에서 프로젝트를 열고 빌드, 설치합니다. 설치 후 "Launch failed"가 뜨는 건 실행할 화면이 없어서이니 무시해도 됩니다.
2. 설정 > 접근성 > 설치된 앱 > **마우스 커서**를 켭니다.
   - 스위치가 막혀 있으면: 설정 > 애플리케이션 > mouseCursor > 우측 상단 ⋮ > **제한된 설정 허용** 후 다시 켭니다.
3. 대상 게임을 실행하면 커서가 나타납니다.

`res/xml/cursor_service.xml`을 바꾼 뒤에는 접근성에서 서비스를 껐다 켜야 반영됩니다.

### 대상 앱 추가

`CursorService.java` 맨 위 목록에 패키지명을 추가하고 다시 빌드합니다. 패키지명은 `DEBUG = true`로 두고 해당 앱을 실행하면 디버그창의 "현재 앱"에 표시됩니다.

```java
private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
        "com.mintrocket.drmobile"   // 데이브 더 다이버 모바일
));
```

### 조정할 수 있는 값

| 상수 | 기본값 | 의미 |
|---|---|---|
| `CURSOR_SIZE_DP` | 24 | 커서 크기 |
| `CURSOR_ALPHA` | 0.75 | 평소 투명도 |
| `PRESSED_SCALE` | 0.8 | 누를 때 크기 비율 |
| `HIDE_DELAY` | 3000 | 자동 숨김까지 시간(ms) |
| `POLL_INTERVAL` | 1000 | 주기 확인 간격(ms) |

### 문제가 생겼을 때

서비스가 켜져 있는 동안 대상 게임에서는 마우스가 이 앱을 거쳐서만 동작합니다. 꼬이면 **볼륨 위아래 버튼을 같이 길게 눌러** 서비스를 끌 수 있습니다(접근성 설정에서 "마우스 커서 바로가기"를 켜둔 경우).

## 권한

| 설정 | 쓰는 이유 |
|---|---|
| `canPerformGestures` | 클릭을 터치로 재현 |
| `canRetrieveWindowContent` | 활성 창의 패키지명 확인 |
| `flagRetrieveInteractiveWindows` | 창 변경 이벤트 수신 |

`canRetrieveWindowContent`는 이론상 화면 내용을 읽을 수 있는 권한이지만, 코드에서는 활성 창의 패키지명만 읽습니다. 인터넷 권한은 요청하지 않아서 밖으로 보내는 데이터는 없습니다.

## 알려진 한계

- **입력 지연**: 터치는 버튼을 뗄 때 실행됩니다. 길게 누르기와 드래그가 실시간이 아닙니다.
- **드래그 경로**: 시작점과 끝점을 잇는 직선으로만 재현합니다. 곡선 드래그나 조이스틱 조작은 제대로 되지 않습니다.
- **제스처 끊김**: 새 제스처가 들어오면 진행 중인 제스처가 취소됩니다. 길게 누른 직후 빠르게 클릭하면 앞의 재현이 끊깁니다.
- **휠, 우클릭**: 대상 게임 안에서는 처리하지 않습니다.
- **하드코딩**: 대상 앱과 무시할 앱 목록이 코드에 있어서, 바꾸려면 다시 빌드해야 합니다.

## 할 일

- [ ] `StrokeDescription`의 `willContinue` / `continueStroke()`로 실시간 터치 전달 (지연, 경로 손실, 끊김을 한 번에 해결)
- [ ] `performTouch` 좌표 검증과 예외 처리
- [ ] 게임 안에서 휠을 스와이프로 변환
- [ ] targetSdk를 35 이상으로 정리
- [ ] 쓰지 않는 의존성(appcompat, constraintlayout, viewBinding) 정리
- [ ] 접근성 설정에 보이는 서비스 설명 문구 작성
