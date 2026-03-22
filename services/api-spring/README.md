# Robot Fleet API Service (`api-spring`)

로봇 관제 플랫폼의 메인 백엔드 서비스입니다. 
로봇의 상태 데이터를 실시간으로 클라이언트에게 제공하고, 사용자로부터 전달된 명령을 로봇에게 전달하는 **관문(Gateway)** 역할을 수행합니다.

## 🚀 Tech Stack

- **Framework**: Spring Boot 3.5.x
- **Language**: Java 25 (Eclipse Temurin)
- **Database**: PostgreSQL 15, Spring Data JPA, Flyway (DB 마이그레이션)
- **Message Broker**: Apache Kafka 7.4.x
- **Real-time Communication**: 
  - Server-Sent Events (SSE) - 단방향 텔레메트리 스트리밍
  - WebSocket (STOMP) - 양방향 로봇 제어 명령 및 수신
- **API Documentation**: Swagger (springdoc-openapi)

## 🏗️ Architecture & Core Responsibilities

본 서비스는 로봇 간의 통신과 프론트엔드 대시보드를 잇는 중간 브리지이며, 핵심 적인 역할은 다음과 같습니다.

### 1. REST API (`RobotController`)
*   로봇 목록, 상태 정보, 배터리 이력, 위치(Pose) 이력 등 저장된 텔레메트리 데이터를 조회합니다.
*   **엔드포인트 문서화**: Swagger UI를 통해 모든 REST API 명세를 제공합니다.

### 2. Real-time Telemetry Stream (`TelemetryStreamController`)
*   **SSE (Server-Sent Events)** 기술을 사용하여 웹 대시보드 클라이언트에게 로봇의 실시간 상태 업데이트를 단방향으로 스트리밍합니다 (`/api/stream/telemetry`).
*   클라이언트는 폴링(Polling) 없이 실시간 데이터를 효과적으로 수신할 수 있습니다.

### 3. Bidirectional Robot Commands (`CommandController`)
*   **WebSocket/STOMP** 프로토콜 기반으로 대시보드에서 보낸 명령(이동, 정지, 작업 지시 등)을 수신합니다 (`/app/command`).
*   수신된 명령은 Kafka를 통해 로봇 네트워크로 퍼블리시(Publish)되고, 비동기 처리가 시작됩니다.

### 4. Kafka Event Driven Integration (`KafkaConfig`, `TelemetryKafkaListener`)
*   **Consumer**: Worker 서비스나 로봇(Fake Robot 포함)이 생산하는 텔레메트리(`telemetry.*`) 및 ACK(`ack.robot`) 메시지를 구독하여 DB 최신화 및 실시간 클라이언트 브로드캐스팅을 수행합니다.
*   **Producer**: 클라이언트가 요청한 Command 명령을 `cmd.robot` 토픽으로 발생시켜 로봇에게 전달합니다.

### 5. 로봇 연결 상태 감지 (`OfflineDetectorService`)
*   주기적 스케줄러(Scheduler)를 통해 로봇의 마지막 통신 시간을 확인합니다.
*   특정 시간(예: 30초) 이상 통신이 두절된 로봇을 오프라인(Offline) 상태로 전환합니다.

## ⚙️ How to Run

### 1. Prerequisites (인프라 환경 셋업)
이 서비스가 동작하기 위해서는 데이터베이스(PostgreSQL)와 메시지 브로커(Kafka)가 필요합니다.
프로젝트 루트 경로(`robot/`)에서 다음 Docker Compose 명령으로 인프라를 백그라운드에서 실행합니다.

```bash
cd ../../  # 로봇 플랫폼 프로젝트 루트 이동
docker compose -f infra/docker-compose.yml up -d postgres kafka zookeeper
```

### 2. Spring Boot 서버 실행
본 디렉토리(`services/api-spring`)에서 Gradle 래퍼를 이용해 애플리케이션을 구동합니다.

```bash
# 로컬 개발 환경 실행
./gradlew bootRun
```
* **포트 정보**: `localhost:8080` 포트로 서버가 띄워집니다.

## 📖 API Documentation (Swagger)

서버가 실행 중인 상태에서 아래 URL로 접속하여 API 명세를 확인하고 직접 테스트해 볼 수 있습니다.

*   **Swagger UI**: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
*   **OpenAPI JSON**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

---
*Generated based on Day 6~7 infrastructure context.*
