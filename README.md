# Spring Boot × Jenkins CI/CD Pipeline

> 로컬에서 `git push` 한 번으로 Ubuntu 서버까지 자동 배포되는 파이프라인 구축기

<br>

## 📌 프로젝트 개요

| 구현 내용 | 설명 |
|---|---|
| **자동 빌드** | Spring Boot 코드 수정 → GitHub push → Jenkins 자동 빌드 |
| **자동 배포** | Jenkins 빌드 결과(JAR) → Docker Volume → Ubuntu 서버 자동 감지 및 재기동 |

<br>

## 🔧 사전 준비 (Prerequisites)

### Ubuntu Server
```bash
sudo apt-get install -y docker.io openjdk-17-jdk inotify-tools
```

### Jenkins (Docker 이미지 기준)
Jenkins 관리 → Plugin Manager에서 아래 플러그인 설치 필수

- `GitHub Integration Plugin`
- `Pipeline Plugin`

### 네트워크 터널링 (ngrok)
```bash
# ngrok 설치 후 인증 토큰 등록
ngrok config add-authtoken <YOUR_AUTHTOKEN>
```
> ⚠️ **ngrok 무료 플랜 주의:** 재시작 시 URL이 변경됨. URL이 바뀔 때마다 GitHub Webhook 주소를 재등록해야 함.

<br>

## 🏗 전체 아키텍처

```
  [Local Dev]         [GitHub]          [Jenkins]            [Ubuntu Server]
      │                  │                  │                       │
      │  1) git push     │                  │                       │
      │─────────────────>│                  │                       │
      │                  │  2) Webhook      │                       │
      │                  │  (POST 요청)     │                       │
      │                  │─────────────────>│                       │
      │                  │                  │  3) Clone & Build     │
      │                  │                  │  (Gradle → JAR)       │
      │                  │                  │                       │
      │                  │                  │  4) JAR 복사          │
      │                  │                  │  (Docker Volume)      │
      │                  │                  │──────────────────────>│
      │                  │                  │                       │  5) inotifywait
      │                  │                  │                       │  변경 감지 → 재기동
```

<br>

## 🛠 단계별 구현

### Step 1. Spring Boot ↔ GitHub 연동

로컬 프로젝트와 원격 저장소를 연결하는 단계임.

```bash
# 최초 1회: 원격 저장소 연결
git init
git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git
git branch -M main
git push -u origin main
```

```bash
# 이후 코드 수정 시
git add .
git commit -m "feat: 변경 내용 설명"
git push origin main
```

> 💡 HTTPS 방식으로 push할 경우, GitHub **Personal Access Token(PAT)** 이 비밀번호 대신 사용됨.  
> `Settings → Developer settings → Personal access tokens`에서 발급 가능.

---

### Step 2. Jenkins 컨테이너 실행 (Docker Volume 마운트)

빌드 결과물(JAR)을 Ubuntu 호스트에 즉시 전달하기 위해 Docker Volume을 활용하는 방식임.

```bash
docker run --name myjenkins2 \
  -p 8090:8080 \
  -v $(pwd)/appjardir:/var/jenkins_home/appjar \
  jenkins/jenkins:lts-jdk17
```

| 경로 | 설명 |
|---|---|
| `/var/jenkins_home/appjar` | Jenkins 컨테이너 내부 경로 |
| `$(pwd)/appjardir` | Ubuntu 호스트의 공유 폴더 |

> Jenkins Pipeline에서 JAR 파일을 `/var/jenkins_home/appjar/`로 복사하면,  
> 볼륨 마운트를 통해 Ubuntu 호스트의 `appjardir/` 폴더에 **실시간으로 반영**됨.

---

### Step 3. GitHub ↔ Jenkins Webhook 연동

외부에서 Jenkins에 접근할 수 있도록 ngrok으로 터널을 열고, GitHub Webhook을 등록하는 단계임.

**① ngrok 터널 실행**
```bash
ngrok http 8090
# → https://xxxx-xxx-xxx.ngrok-free.app 형태의 공인 URL 발급
```

**② GitHub Webhook 등록**

`Repository → Settings → Webhooks → Add webhook`

```
Payload URL : http://<ngrok-url>/github-webhook/
Content type: application/json
Event       : Just the push event
```

**③ Jenkins 빌드 트리거 활성화**

`Jenkins Job → 구성 → 빌드 유발` 에서 **"GitHub hook trigger for GITScm polling"** 체크

---

### Step 4. Jenkins Pipeline Script

GitHub에서 코드를 받아 Gradle로 빌드하고, JAR를 공유 폴더로 복사하는 스크립트임.

```groovy
pipeline {
    agent any

    stages {
        stage('Clone Repository') {
            steps {
                // ✅ 본인 GitHub URL로 변경
                git branch: 'main', url: 'https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git'
            }
        }

        stage('Build') {
            steps {
                dir('./') {
                    sh 'chmod +x gradlew'
                    sh './gradlew clean build -x test'
                    echo "Workspace: ${WORKSPACE}"
                }
            }
        }

        stage('Copy JAR') {
            steps {
                script {
                    // ✅ build.gradle의 프로젝트명·버전에 맞게 변경
                    def jarFile = './build/libs/<YOUR_PROJECT>-0.0.1-SNAPSHOT.jar'
                    def destinationDir = '/var/jenkins_home/appjar/'

                    sh "cp ${jarFile} ${destinationDir}"
                }
            }
        }
    }
}
```

---

### Step 5. JAR 변경 감지 및 자동 재기동 (deploy.sh)

`inotifywait`으로 JAR 파일 변경을 감지하고, 자동으로 서버를 재기동하는 스크립트임.

```bash
#!/bin/bash

APP_PATH="/home/soon/appjardir"
JAR_NAME="<YOUR_PROJECT>-0.0.1-SNAPSHOT.jar"  # ✅ 프로젝트에 맞게 변경
TARGET_PORT=80

echo "🚀 CI/CD 모니터링 시작: $APP_PATH/$JAR_NAME"

# 파일의 수정(modify) 및 쓰기 완료(close_write) 이벤트 감지
inotifywait -m -e modify,close_write "$APP_PATH/$JAR_NAME" | while read path action file
do
    echo "------------------------------------------------------"
    echo "$(date): $JAR_NAME 변경 감지 → 재배포 시작"

    # 1. 기존 프로세스 종료 (포트 기준)
    echo "▶ Step 1: 포트 $TARGET_PORT 점유 프로세스 종료"
    sudo fuser -k $TARGET_PORT/tcp
    sleep 2

    # 2. 잔여 프로세스 체크 (파일명 기준)
    CURRENT_PID=$(pgrep -f $JAR_NAME)
    if [ -n "$CURRENT_PID" ]; then
        sudo kill -9 $CURRENT_PID
        sleep 1
    fi

    # 3. 새로운 JAR 실행
    echo "▶ Step 2: 새로운 서버 기동"
    sudo nohup java -jar $APP_PATH/$JAR_NAME > $APP_PATH/log.out 2>&1 &

    echo "✅ 재기동 완료. 로그 확인: tail -f $APP_PATH/log.out"
    echo "------------------------------------------------------"
done
```

**스크립트 실행 방법**
```bash
chmod +x deploy.sh   # 최초 1회: 실행 권한 부여
./deploy.sh          # 백그라운드 모니터링 시작
```

<br>

## ▶ 전체 동작 시나리오

```
1. Ubuntu에서 JAR 파일 최초 실행
   $ sudo java -jar ~/appjardir/<YOUR_PROJECT>.jar

2. 다른 터미널에서 deploy.sh 실행 (변경 감지 대기)
   $ ./deploy.sh

3. Spring Boot 코드 수정 후 push
   $ git add . && git commit -m "fix: 버그 수정" && git push origin main

4. GitHub → ngrok → Jenkins 로 Webhook 신호 전달

5. Jenkins Pipeline 자동 실행
   ├─ GitHub에서 최신 코드 Clone
   ├─ Gradle로 JAR 빌드
   └─ Docker Volume 경로로 JAR 복사

6. deploy.sh가 파일 변경 감지 (inotifywait)
   ├─ 기존 프로세스 종료
   └─ 새 JAR로 서버 재기동

7. 브라우저 새로고침 → 변경 내용 즉시 확인 ✅
```

<br>

## 🚨 트러블슈팅

<!-- 겪었던 오류와 해결 방법을 이곳에 추가 -->

| 증상 | 원인 | 해결 방법 |
|---|---|---|
| Webhook 연결 안 됨 | ngrok 재시작 후 URL 변경 | GitHub Webhook URL 재등록 |
| 포트 80 접근 거부 | 권한 부족 | `sudo`로 실행 또는 포트 8080으로 변경 |
| JAR 파일 못 찾음 | build.gradle 파일명 불일치 | Pipeline Script의 JAR 경로 확인 |