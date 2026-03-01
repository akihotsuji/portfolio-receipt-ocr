# API 設計書

## 1. 概要

領収書 OCR アプリケーションの REST API 設計。
フロントエンド（React）からバックエンド（Spring Boot）への通信インターフェースを定義する。

### 対象フロー

| フロー | 概要 | 関連画面 |
|---|---|---|
| フロー① | ファイルアップロード → OCR 処理 → 確認・編集 → 確定 | 画面A, 画面B |
| フロー② | 確定データ一覧 → ソート・フィルタ → CSV エクスポート | 画面C |

---

## 2. 共通仕様

### 2.1 ベース URL

```
http://localhost:8080/api
```

### 2.2 認証

全エンドポイントで JWT 認証が必要。

```
Authorization: Bearer <JWT トークン>
```

- Portal API の JWKS エンドポイントから公開鍵を取得して検証
- トークンが無効・期限切れの場合は `401 Unauthorized` を返却

### 2.3 リクエスト・レスポンス形式

| 項目 | 仕様 |
|---|---|
| Content-Type | `application/json`（ファイルアップロードのみ `multipart/form-data`） |
| 文字コード | UTF-8 |
| 日時形式 | ISO 8601（`2026-02-28T12:00:00Z`） |
| ID 形式 | UUID v4 |

### 2.4 共通エラーレスポンス

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "ファイルサイズが上限を超えています",
    "details": [
      {
        "field": "files",
        "message": "最大ファイルサイズは 10MB です"
      }
    ]
  }
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| error.code | string | ○ | エラーコード（後述の一覧参照） |
| error.message | string | ○ | エラーの概要メッセージ |
| error.details | array | - | フィールドレベルの詳細（バリデーションエラー時） |

### 2.5 エラーコード一覧

| コード | HTTP ステータス | 説明 |
|---|---|---|
| VALIDATION_ERROR | 400 | リクエストパラメータの検証エラー |
| UNAUTHORIZED | 401 | 認証エラー（トークン無効・期限切れ） |
| FORBIDDEN | 403 | 権限エラー（他ユーザーのリソースへのアクセス） |
| NOT_FOUND | 404 | リソースが存在しない |
| FILE_TOO_LARGE | 400 | ファイルサイズが上限（10MB）を超過 |
| FILE_TYPE_NOT_ALLOWED | 400 | 許可されていないファイル形式 |
| TOO_MANY_FILES | 400 | アップロード枚数が上限（10枚）を超過 |
| JOB_NOT_RETRYABLE | 409 | リトライ不可能な状態のジョブ |
| EXPORT_NOT_READY | 409 | エクスポートがまだ完了していない |
| INTERNAL_ERROR | 500 | サーバー内部エラー |

### 2.6 HTTP ステータスコード

| コード | 用途 |
|---|---|
| 200 | 正常（取得・更新） |
| 201 | 正常（作成） |
| 204 | 正常（削除、レスポンスボディなし） |
| 400 | バリデーションエラー |
| 401 | 認証エラー |
| 403 | 権限エラー |
| 404 | リソース未検出 |
| 409 | 状態不整合 |
| 500 | サーバー内部エラー |

---

## 3. ステータス定義

### 3.1 OCR ジョブステータス

```
QUEUED → PROCESSING → COMPLETED → CONFIRMED
                ↓
             FAILED
```

| ステータス | 説明 |
|---|---|
| QUEUED | キューに投入済み、処理待ち |
| PROCESSING | OCR 処理中 |
| COMPLETED | OCR 処理完了、ユーザーの確認待ち |
| CONFIRMED | ユーザーが確認・編集して確定済み |
| FAILED | 自動リトライ（最大3回）を含めすべて失敗 |

### 3.2 エクスポートジョブステータス

```
QUEUED → PROCESSING → COMPLETED
                ↓
             FAILED
```

| ステータス | 説明 |
|---|---|
| QUEUED | キューに投入済み、処理待ち |
| PROCESSING | CSV 生成中 |
| COMPLETED | CSV 生成完了、ダウンロード可能 |
| FAILED | エクスポート処理失敗 |

---

## 4. エンドポイント一覧

### フロー①: アップロード → OCR → 確認・確定

| メソッド | パス | 概要 |
|---|---|---|
| POST | /api/upload-batches | ファイルアップロード（バッチ作成） |
| GET | /api/upload-batches | バッチ一覧取得（ページング・ソート） |
| GET | /api/upload-batches/{batchId} | バッチ情報・全ジョブステータス取得 |
| GET | /api/ocr-jobs/{jobId} | ジョブ詳細・OCR 結果取得 |
| GET | /api/ocr-jobs/{jobId}/image-url | 画像の署名付き URL 取得 |
| POST | /api/ocr-jobs/{jobId}/confirm | OCR 結果を確認・編集して確定 |
| POST | /api/ocr-jobs/{jobId}/retry | 失敗ジョブの手動リトライ |

### フロー②: 一覧 → エクスポート

| メソッド | パス | 概要 |
|---|---|---|
| GET | /api/receipts | 確定済みレシート一覧（ページング・ソート・フィルタ） |
| GET | /api/receipts/{receiptId} | レシート詳細取得 |
| PUT | /api/receipts/{receiptId} | レシート編集 |
| DELETE | /api/receipts/{receiptId} | レシート削除 |
| POST | /api/exports | CSV エクスポートジョブ作成 |
| GET | /api/exports/{exportId} | エクスポートジョブステータス取得 |
| GET | /api/exports/{exportId}/download | CSV ファイルダウンロード |

---

## 5. API 詳細

### 5.1 アップロードバッチ

#### POST /api/upload-batches

ファイルをアップロードし、OCR 処理バッチを作成する。

**リクエスト**

- Content-Type: `multipart/form-data`

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| files | File[] | ○ | アップロードファイル（1〜10枚） |

**バリデーション**

| ルール | エラーコード |
|---|---|
| ファイル数: 1〜10 枚 | TOO_MANY_FILES |
| ファイルサイズ: 10MB 以下 / 枚 | FILE_TOO_LARGE |
| ファイル形式: JPEG, PNG, PDF | FILE_TYPE_NOT_ALLOWED |

**レスポンス: 201 Created**

```json
{
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "jobs": [
    {
      "jobId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
      "fileName": "receipt_001.jpg",
      "fileSize": 2048576,
      "mimeType": "image/jpeg",
      "status": "QUEUED",
      "createdAt": "2026-02-28T12:00:00Z"
    },
    {
      "jobId": "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
      "fileName": "receipt_002.pdf",
      "fileSize": 1048576,
      "mimeType": "application/pdf",
      "status": "QUEUED",
      "createdAt": "2026-02-28T12:00:00Z"
    }
  ],
  "totalFiles": 2,
  "createdAt": "2026-02-28T12:00:00Z"
}
```

**処理フロー**

1. 各ファイルを MinIO（`receipt-uploads` バケット）に保存
2. ファイルごとに OCR ジョブレコードを DB に作成（ステータス: QUEUED）
3. ジョブをキューに投入（ワーカーが非同期に処理）
4. バッチ情報とジョブ一覧を返却

---

#### GET /api/upload-batches

ログインユーザーのバッチ一覧を取得する。画面B のバッチ履歴サイドバー表示用。

**クエリパラメータ**

| パラメータ | 型 | デフォルト | 説明 |
|---|---|---|---|
| page | integer | 0 | ページ番号（0始まり） |
| size | integer | 20 | 1ページあたりの件数（最大100） |
| sort | string | createdAt | ソート項目（createdAt） |
| order | string | desc | ソート順（asc, desc） |

**レスポンス: 200 OK**

```json
{
  "content": [
    {
      "batchId": "550e8400-e29b-41d4-a716-446655440000",
      "totalFiles": 3,
      "summary": {
        "total": 3,
        "queued": 0,
        "processing": 0,
        "completed": 1,
        "confirmed": 2,
        "failed": 0
      },
      "createdAt": "2026-02-28T12:00:00Z"
    },
    {
      "batchId": "660f9511-f3ac-52e5-b827-557766551111",
      "totalFiles": 2,
      "summary": {
        "total": 2,
        "queued": 0,
        "processing": 0,
        "completed": 0,
        "confirmed": 2,
        "failed": 0
      },
      "createdAt": "2026-02-27T10:30:00Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 5,
    "totalPages": 1
  }
}
```

**content 各要素のフィールド詳細**

| フィールド | 型 | 説明 |
|---|---|---|
| batchId | UUID | バッチ ID |
| totalFiles | integer | アップロードファイル数 |
| summary.total | integer | ジョブ総数 |
| summary.queued | integer | 待機中のジョブ数 |
| summary.processing | integer | 処理中のジョブ数 |
| summary.completed | integer | 完了（未確定）のジョブ数 |
| summary.confirmed | integer | 確定済みのジョブ数 |
| summary.failed | integer | 失敗したジョブ数 |
| createdAt | string (datetime) | バッチ作成日時 |

`summary` は `GET /api/upload-batches/{batchId}` のレスポンスと同じ構造。サイドバーでバッチごとの進捗状況を表示するために使用する。

---

#### GET /api/upload-batches/{batchId}

バッチに含まれる全ジョブの最新ステータスを取得する。フロントエンドのポーリング用。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| batchId | UUID | バッチ ID |

**レスポンス: 200 OK**

```json
{
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "jobs": [
    {
      "jobId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
      "fileName": "receipt_001.jpg",
      "status": "COMPLETED",
      "retryCount": 0,
      "createdAt": "2026-02-28T12:00:00Z",
      "completedAt": "2026-02-28T12:00:15Z"
    },
    {
      "jobId": "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
      "fileName": "receipt_002.pdf",
      "status": "PROCESSING",
      "retryCount": 0,
      "createdAt": "2026-02-28T12:00:00Z",
      "completedAt": null
    }
  ],
  "summary": {
    "total": 2,
    "queued": 0,
    "processing": 1,
    "completed": 1,
    "confirmed": 0,
    "failed": 0
  },
  "createdAt": "2026-02-28T12:00:00Z"
}
```

`summary` フィールドにより、フロントエンドはプログレスバー表示やポーリング停止判定を効率的に行える。

---

### 5.2 OCR ジョブ

#### GET /api/ocr-jobs/{jobId}

ジョブの詳細と OCR 読み取り結果を取得する。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| jobId | UUID | ジョブ ID |

**レスポンス: 200 OK（ステータスが COMPLETED の場合）**

```json
{
  "jobId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "receipt_001.jpg",
  "fileSize": 2048576,
  "mimeType": "image/jpeg",
  "status": "COMPLETED",
  "retryCount": 0,
  "ocrResult": {
    "date": "2026-02-15",
    "amount": 3980,
    "storeName": "東京文具店",
    "description": "コピー用紙 A4 500枚",
    "taxCategory": "10%",
    "rawText": "領収書\n東京文具店\n2026年2月15日\nコピー用紙 A4 500枚\n¥3,980（税込10%）\n..."
  },
  "createdAt": "2026-02-28T12:00:00Z",
  "completedAt": "2026-02-28T12:00:15Z"
}
```

**ocrResult フィールド詳細**

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| date | string (date) | - | 取引日（YYYY-MM-DD）。読み取れなかった場合は null |
| amount | integer | - | 支払総額（円）。読み取れなかった場合は null |
| storeName | string | - | 支払先（店名）。読み取れなかった場合は null |
| description | string | - | 品目・明細。読み取れなかった場合は null |
| taxCategory | string | - | 税区分（"8%", "10%" 等）。読み取れなかった場合は null |
| rawText | string | ○ | OCR が読み取った生テキスト全文 |

**レスポンス: 200 OK（ステータスが FAILED の場合）**

```json
{
  "jobId": "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
  "batchId": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "receipt_002.pdf",
  "fileSize": 1048576,
  "mimeType": "application/pdf",
  "status": "FAILED",
  "retryCount": 3,
  "ocrResult": null,
  "errorMessage": "OCR 処理がタイムアウトしました（60秒）",
  "createdAt": "2026-02-28T12:00:00Z",
  "completedAt": null
}
```

---

#### GET /api/ocr-jobs/{jobId}/image-url

画像プレビュー用の署名付き URL を取得する。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| jobId | UUID | ジョブ ID |

**レスポンス: 200 OK**

```json
{
  "imageUrl": "https://minio.example.com/receipt-uploads/550e8400/receipt_001.jpg?X-Amz-Signature=...",
  "expiresAt": "2026-02-28T12:15:00Z"
}
```

| フィールド | 型 | 説明 |
|---|---|---|
| imageUrl | string | 署名付き URL（有効期限付き） |
| expiresAt | string (datetime) | URL の有効期限（発行から15分） |

- PDF の場合は、変換済みの画像（1ページ目）の URL を返却する

---

#### POST /api/ocr-jobs/{jobId}/confirm

OCR 結果を確認・編集して確定する。確定すると「レシート」として保存される。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| jobId | UUID | ジョブ ID |

**前提条件**

- ジョブステータスが `COMPLETED` であること（それ以外は `409 JOB_NOT_RETRYABLE`）

**リクエスト**

```json
{
  "date": "2026-02-15",
  "amount": 3980,
  "storeName": "東京文具店",
  "description": "コピー用紙 A4 500枚",
  "taxCategory": "10%"
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| date | string (date) | ○ | 取引日（YYYY-MM-DD） |
| amount | integer | ○ | 支払総額（円、1 以上） |
| storeName | string | ○ | 支払先（1〜200文字） |
| description | string | - | 品目・明細（最大500文字） |
| taxCategory | string | - | 税区分（"8%", "10%" 等） |

**レスポンス: 201 Created**

```json
{
  "receiptId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "jobId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "date": "2026-02-15",
  "amount": 3980,
  "storeName": "東京文具店",
  "description": "コピー用紙 A4 500枚",
  "taxCategory": "10%",
  "confirmedAt": "2026-02-28T12:05:00Z"
}
```

**処理フロー**

1. リクエストボディをバリデーション
2. ジョブステータスを `CONFIRMED` に更新
3. レシートレコードを DB に作成
4. 作成されたレシートを返却

---

#### POST /api/ocr-jobs/{jobId}/retry

失敗したジョブの手動リトライを実行する。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| jobId | UUID | ジョブ ID |

**前提条件**

- ジョブステータスが `FAILED` であること（それ以外は `409 JOB_NOT_RETRYABLE`）

**リクエスト**

リクエストボディなし。

**レスポンス: 200 OK**

```json
{
  "jobId": "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
  "status": "QUEUED",
  "retryCount": 0,
  "message": "ジョブを再キューイングしました"
}
```

**処理フロー**

1. ジョブステータスを `QUEUED` にリセット
2. リトライカウントを 0 にリセット
3. ジョブをキューに再投入

---

### 5.3 レシート（確定データ）

#### GET /api/receipts

確定済みレシートの一覧を取得する。ページング・ソート・フィルタに対応。

**クエリパラメータ**

| パラメータ | 型 | デフォルト | 説明 |
|---|---|---|---|
| page | integer | 0 | ページ番号（0始まり） |
| size | integer | 20 | 1ページあたりの件数（最大100） |
| sort | string | date | ソート項目（date, amount, storeName, confirmedAt） |
| order | string | desc | ソート順（asc, desc） |
| dateFrom | string (date) | - | 取引日の範囲開始（YYYY-MM-DD） |
| dateTo | string (date) | - | 取引日の範囲終了（YYYY-MM-DD） |
| storeName | string | - | 店名の部分一致検索 |
| amountMin | integer | - | 金額の下限 |
| amountMax | integer | - | 金額の上限 |

**レスポンス: 200 OK**

```json
{
  "content": [
    {
      "receiptId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "date": "2026-02-15",
      "amount": 3980,
      "storeName": "東京文具店",
      "description": "コピー用紙 A4 500枚",
      "taxCategory": "10%",
      "confirmedAt": "2026-02-28T12:05:00Z"
    },
    {
      "receiptId": "8a7b6c5d-4e3f-2a1b-0c9d-8e7f6a5b4c3d",
      "date": "2026-02-10",
      "amount": 1200,
      "storeName": "カフェ・ド・パリ",
      "description": "打ち合わせ コーヒー代",
      "taxCategory": "8%",
      "confirmedAt": "2026-02-28T11:30:00Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 42,
    "totalPages": 3
  }
}
```

---

#### GET /api/receipts/{receiptId}

レシートの詳細を取得する。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| receiptId | UUID | レシート ID |

**レスポンス: 200 OK**

```json
{
  "receiptId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "jobId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "date": "2026-02-15",
  "amount": 3980,
  "storeName": "東京文具店",
  "description": "コピー用紙 A4 500枚",
  "taxCategory": "10%",
  "confirmedAt": "2026-02-28T12:05:00Z",
  "updatedAt": "2026-02-28T12:05:00Z"
}
```

---

#### PUT /api/receipts/{receiptId}

確定済みレシートの内容を修正する。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| receiptId | UUID | レシート ID |

**リクエスト**

```json
{
  "date": "2026-02-15",
  "amount": 4280,
  "storeName": "東京文具店",
  "description": "コピー用紙 A4 500枚、ボールペン",
  "taxCategory": "10%"
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| date | string (date) | ○ | 取引日（YYYY-MM-DD） |
| amount | integer | ○ | 支払総額（円、1 以上） |
| storeName | string | ○ | 支払先（1〜200文字） |
| description | string | - | 品目・明細（最大500文字） |
| taxCategory | string | - | 税区分 |

**レスポンス: 200 OK**

```json
{
  "receiptId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "date": "2026-02-15",
  "amount": 4280,
  "storeName": "東京文具店",
  "description": "コピー用紙 A4 500枚、ボールペン",
  "taxCategory": "10%",
  "confirmedAt": "2026-02-28T12:05:00Z",
  "updatedAt": "2026-02-28T12:10:00Z"
}
```

---

#### DELETE /api/receipts/{receiptId}

レシートを削除する。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| receiptId | UUID | レシート ID |

**レスポンス: 204 No Content**

レスポンスボディなし。

---

### 5.4 エクスポート

#### POST /api/exports

選択したレシートの CSV エクスポートジョブを作成する。

**リクエスト**

```json
{
  "receiptIds": [
    "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "8a7b6c5d-4e3f-2a1b-0c9d-8e7f6a5b4c3d"
  ]
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| receiptIds | UUID[] | ○ | エクスポート対象のレシート ID 一覧（1件以上） |

**レスポンス: 201 Created**

```json
{
  "exportId": "9f8e7d6c-5b4a-3c2d-1e0f-a9b8c7d6e5f4",
  "status": "QUEUED",
  "totalReceipts": 2,
  "createdAt": "2026-02-28T12:10:00Z"
}
```

**処理フロー**

1. エクスポートジョブレコードを DB に作成（ステータス: QUEUED）
2. ジョブをキューに投入（Lambda が非同期に CSV 生成）
3. 生成した CSV を S3 に保存
4. ジョブステータスを COMPLETED に更新

---

#### GET /api/exports/{exportId}

エクスポートジョブのステータスを取得する。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| exportId | UUID | エクスポート ID |

**レスポンス: 200 OK**

```json
{
  "exportId": "9f8e7d6c-5b4a-3c2d-1e0f-a9b8c7d6e5f4",
  "status": "COMPLETED",
  "totalReceipts": 2,
  "fileName": "receipts_20260228_121000.csv",
  "createdAt": "2026-02-28T12:10:00Z",
  "completedAt": "2026-02-28T12:10:05Z"
}
```

---

#### GET /api/exports/{exportId}/download

エクスポート済み CSV ファイルをダウンロードする。

**パスパラメータ**

| パラメータ | 型 | 説明 |
|---|---|---|
| exportId | UUID | エクスポート ID |

**前提条件**

- エクスポートステータスが `COMPLETED` であること（それ以外は `409 EXPORT_NOT_READY`）

**レスポンス: 200 OK**

```
Content-Type: text/csv; charset=UTF-8
Content-Disposition: attachment; filename="receipts_20260228_121000.csv"
```

**CSV フォーマット**

```csv
日付,金額,支払先,品目,税区分
2026-02-15,3980,東京文具店,コピー用紙 A4 500枚,10%
2026-02-10,1200,カフェ・ド・パリ,打ち合わせ コーヒー代,8%
```

| 列 | 説明 |
|---|---|
| 日付 | 取引日（YYYY-MM-DD） |
| 金額 | 支払総額（円） |
| 支払先 | 店名 |
| 品目 | 明細 |
| 税区分 | 8% / 10% 等 |

---

## 6. ポーリング設計

### 6.1 画面B: OCR 処理ステータスのポーリング

フロントエンドはアップロード後、バッチのステータスを定期的にポーリングして処理進捗を表示する。

| 項目 | 仕様 |
|---|---|
| 対象エンドポイント | GET /api/upload-batches/{batchId} |
| ポーリング間隔 | 2秒 |
| 停止条件 | 全ジョブが終端ステータス（COMPLETED / CONFIRMED / FAILED）に到達 |

**フロントエンド処理フロー**

```
1. 画面B の初期表示時に GET /api/upload-batches でバッチ一覧を取得（サイドバー表示）
2. POST /api/upload-batches でアップロード → batchId を取得し画面B へ遷移
   （またはサイドバーからバッチを選択して画面B へ遷移）
3. 2秒ごとに GET /api/upload-batches/{batchId} をポーリング
4. summary.queued + summary.processing が 0 になったらポーリング停止
5. COMPLETED のジョブは確認・編集フォームを表示可能にする
6. FAILED のジョブはリトライボタンを表示する
```

### 6.2 画面C: エクスポートステータスのポーリング

| 項目 | 仕様 |
|---|---|
| 対象エンドポイント | GET /api/exports/{exportId} |
| ポーリング間隔 | 2秒 |
| 停止条件 | ステータスが COMPLETED または FAILED に到達 |

**フロントエンド処理フロー**

```
1. POST /api/exports でエクスポートジョブ作成 → exportId を取得
2. 2秒ごとに GET /api/exports/{exportId} をポーリング
3. COMPLETED になったら自動でダウンロード開始（GET .../download）
4. FAILED の場合はエラーメッセージを表示
```

---

## 7. API とユーザーフローの対応

### フロー①: アップロード → OCR → 確認・確定

```
画面A                        画面B
──────                      ──────────────────────────────────
ファイル選択                  [サイドバー]        [メインエリア]
    │                     バッチ履歴一覧      ステータス一覧（ポーリング）
    │                          │                    │
    ▼                          ▼                    ▼
POST /upload-batches    GET /upload-batches   GET /upload-batches/{batchId} (×N回)
    │                   (バッチ一覧取得)             │
    │                          │              ジョブ COMPLETED
    │                     バッチ選択                 │
    │                     (クリック)                 ▼
    └──→ navigate(/batch/{batchId}) ←─┘    GET /ocr-jobs/{jobId}
                                            GET /ocr-jobs/{jobId}/image-url
                                                  │
                                             編集フォーム表示
                                                  │
                                                  ▼
                                            POST /ocr-jobs/{jobId}/confirm
                                                  │
                                             ジョブ FAILED の場合
                                                  │
                                                  ▼
                                            POST /ocr-jobs/{jobId}/retry
```

### フロー②: 一覧 → エクスポート

```
画面C
──────────────────────────────────────────
GET /api/receipts?page=0&size=20&sort=date
    │
    ▼
一覧表示 + ソート・フィルタ
    │
チェックボックスで対象選択
    │
    ▼
POST /api/exports
    │
    ▼
GET /api/exports/{exportId} (×N回ポーリング)
    │
    ▼
GET /api/exports/{exportId}/download
```

---

**作成日**: 2026-02-28
**版数**: 1.2
**ステータス**: バッチ一覧 API 追加
