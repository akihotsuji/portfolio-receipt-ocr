# 領収書 OCR

領収書の読み取り・データ出力を行うマイクロアプリ

## 概要

領収書 OCR は、ファイルアップロード・非同期処理・並列処理・冪等性をテーマとしたマイクロアプリです。
Portal API が発行した JWT を使用して認証を行います。

## 技術スタック

- **バックエンド**: Spring Boot 4.x + Java 21 + PostgreSQL
- **フロントエンド**: React 19 + TypeScript + Vite + Tailwind CSS
- **認証**: JWT 検証（Portal API の JWKS から公開鍵取得）

## セットアップ

### 前提条件

- Node.js 20+
- Java 21+
- Docker / Docker Compose
- PostgreSQL 16

### バックエンド起動

```bash
cd backend
./mvnw spring-boot:run
```

http://localhost:8083/api/actuator/health

### フロントエンド起動

```bash
cd frontend
npm install
npm run dev
```

http://localhost:5175/receipt-ocr/

### 統合環境（Docker Compose）

```bash
cd ../infrastructure/docker
docker compose -f docker-compose.all.yml up -d
```

http://localhost:3000/receipt-ocr/

## ステータス

- [x] プロジェクトスケルトン作成
- [ ] 要件定義
- [ ] 基本設計
- [ ] 詳細設計
- [ ] バックエンド実装
- [ ] フロントエンド実装
- [ ] 統合テスト
- [ ] デプロイ

---

**作成日**: 2026-02-27
