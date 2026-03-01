# 領収書 OCR フロントエンド - タスク一覧

## 🎯 概要

領収書 OCR アプリケーションのフロントエンド（React + TypeScript）の実装タスク一覧。
3画面（アップロード / OCR 処理・確認 / データ一覧・エクスポート）を Feature ベース構成で実装し、
React Context による通知管理とポーリングによる非同期処理のステータス追跡を学習テーマとする。

## 🏗️ アーキテクチャ上の重要ポイント

### Feature ベース構成（重要！）

**画面単位で3つの Feature モジュールに分割する**

- `upload`（画面A）: アップロード
- `ocrjob`（画面B）: 処理状況・確認・編集
- `receipt`（画面C）: データ一覧・エクスポート

各 Feature は `api/`, `components/`, `hooks/`, `types/` の4サブディレクトリで責務を分離する。

**実装への影響:**

```
features/
├── upload/      # 画面A に閉じたコード
├── ocrjob/      # 画面B に閉じたコード
└── receipt/     # 画面C に閉じたコード（エクスポートも含む）
```

### React Context の利用範囲（重要！）

**サーバー状態には使わず、UI 横断の状態（通知）のみに限定する**

- 通知（トースト） → Context
- バッチ ID → URL パラメータ
- フィルタ・ソート → Feature 内の useState
- API レスポンスデータ → カスタムフック内の useState

### ページコンポーネントは薄く保つ（重要！）

ページコンポーネントは Feature のコンポーネントを配置するだけの薄いラッパー。
ビジネスロジックはすべてカスタムフックに集約する。

---

## 📱 機能要件

### 1. アップロード画面（画面A）

- ドラッグ＆ドロップまたはボタンでファイル選択（JPEG, PNG, PDF）
- 最大10枚まで同時選択可能
- ファイルサイズ上限 10MB / 枚
- バリデーション（形式・サイズ・枚数）をフロントエンドで事前チェック
- アップロード実行後、画面B へ遷移

### 2. 処理状況 + 確認・編集画面（画面B）

- プログレスバー + ステータス別件数バッジ
- ジョブ一覧（QUEUED / PROCESSING / COMPLETED / CONFIRMED / FAILED）
- COMPLETED のジョブ選択 → 画像プレビュー + 編集フォーム表示
- 5フィールド（日付・金額・店名・品目・税区分）の確認・修正・確定
- OCR 生テキストのトグル表示
- 失敗ジョブの手動リトライ
- バッチステータスの2秒間隔ポーリング

### 3. データ一覧 + エクスポート画面（画面C）

- 確定済みレシートのテーブル表示（ページング対応）
- フィルタ（期間・店名・金額範囲）+ ソート
- チェックボックスによる選択（個別・全選択）
- レシートの編集（モーダル）・削除（確認ダイアログ）
- CSV エクスポート（非同期ジョブ → ポーリング → 自動ダウンロード）

---

## 🏗️ 技術スタック

- **言語**: TypeScript
- **フレームワーク**: React 19 + Vite
- **スタイリング**: Tailwind CSS 4
- **ルーティング**: React Router 7
- **HTTP クライアント**: Axios（設定済み: `lib/axios.ts`）
- **状態管理**: React Context（通知のみ）+ カスタムフック内 useState
- **テスト**: Vitest

---

## 📋 タスクリスト

### Phase 1: 共通基盤

**この Phase のゴール**:
全画面で共有する型定義・ユーティリティ・共通コンポーネント・レイアウト・ルーティングが整い、
Feature 実装に着手できる土台が完成した状態にする。

**共通参照**:

- `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` - ディレクトリ構成・コンポーネント一覧
- `@docs/2.基本設計/API設計書.md` - §2.4 共通エラーレスポンス形式
- `@docs/3.詳細設計/エラーハンドリング設計書.md` - §7 フロントエンドエラーハンドリング

---

- [ ] **Task 1.1**: 共通 API 型定義の作成

  - **目的**: API レスポンスの共通構造（エラーレスポンス・ページネーション）を型で定義し、
    各 Feature の API サービスから一貫して利用できるようにする
  - **やること**:
    - `src/types/api.ts` に以下の型を定義:
      - `ApiError`（error.code, error.message, error.details）
      - `FieldError`（field, message）
      - `PageResponse<T>`（content, page: { number, size, totalElements, totalPages }）
    - 型ガード関数 `isApiError(error)` を実装
    - ヘルパー関数 `getErrorCode(error)`, `getFieldErrors(error)` を実装
  - **完了条件**:
    - `src/types/api.ts` が存在し、上記の型と関数がエクスポートされている
    - TypeScript の型チェックが通る
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §2.4 共通エラーレスポンス
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §7.3 API エラーレスポンス型
  - **推定時間**: 30分

---

- [ ] **Task 1.2**: ユーティリティ関数の作成

  - **目的**: 全画面で使用する表示フォーマット変換とファイルバリデーション関数を共通化する
  - **やること**:
    - `src/utils/format.ts` に以下を実装:
      - `formatCurrency(amount: number)` → `¥3,980` 形式
      - `formatFileSize(bytes: number)` → `2.0 MB` 形式
      - `formatDate(dateStr: string)` → `2026/02/15` 形式
      - `formatDateTime(dateTimeStr: string)` → `2026/02/15 12:00` 形式
    - `src/utils/validation.ts` に以下を実装:
      - `isAllowedFileType(file: File)` → JPEG, PNG, PDF のみ許可
      - `isFileSizeWithinLimit(file: File)` → 10MB 以下
      - `isFileCountWithinLimit(count: number)` → 10枚以下
      - 定数: `MAX_FILE_SIZE = 10 * 1024 * 1024`, `MAX_FILE_COUNT = 10`, `ALLOWED_MIME_TYPES`
  - **完了条件**:
    - 各関数がエクスポートされている
    - 単体テストが存在し、Vitest で全件パスする
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §8.4 utils
    - `@docs/1.要件定義/要件定義書.md` の §3.1 ファイルアップロード仕様
  - **推定時間**: 1時間

---

- [ ] **Task 1.3**: Axios インスタンスのエラーハンドリング強化

  - **目的**: 既存の Axios インスタンスに 403 / 500 の共通エラー処理を追加し、
    Feature 側で個別処理が不要なエラーを一元的に処理する
  - **やること**:
    - `src/lib/axios.ts` のレスポンスインターセプターに以下を追加:
      - 403 → NotificationContext 経由で「アクセス権限がありません」を表示
      - 500 → NotificationContext 経由で「サーバーエラーが発生しました」を表示
    - 401 の既存処理（トークンリフレッシュ → ログインリダイレクト）は維持
    - NotificationContext の showError を呼び出せるようにする（Context の実装後に統合）
  - **完了条件**:
    - 403 / 500 レスポンス時に共通の通知が表示される
    - 既存の 401 ハンドリングが壊れていない
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §7.1 共通エラー処理
    - `frontend/src/lib/axios.ts`（既存コード）
  - **依存**: Task 1.6（NotificationContext）の完了後に統合
  - **推定時間**: 30分

---

- [ ] **Task 1.4**: 共通 UI コンポーネントの作成（Button / Spinner）

  - **目的**: 全画面で頻繁に使用する基本 UI コンポーネントを先行して実装し、
    Feature コンポーネントの実装時にすぐ利用できるようにする
  - **やること**:
    - `src/components/common/Button.tsx`:
      - variant（primary / secondary / text / danger）
      - ローディング状態（Spinner 表示 + disabled）
      - size（sm / md / lg）
      - Tailwind CSS でスタイリング
    - `src/components/common/Spinner.tsx`:
      - サイズ（sm / md / lg）
      - Tailwind CSS のアニメーションで回転するスピナー
    - `src/components/common/index.ts` にバレルエクスポート
  - **完了条件**:
    - Button がローディング中に Spinner を表示し、クリック無効になる
    - 各 variant / size が Tailwind CSS でスタイル適用されている
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §4.1 共通コンポーネント
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（画面デザイン）
  - **推定時間**: 1時間

---

- [ ] **Task 1.5**: 共通 UI コンポーネントの作成（Modal / ConfirmDialog / Notification）

  - **目的**: 画面C で使用するモーダル・確認ダイアログと、全画面で使用するトースト通知を実装する
  - **やること**:
    - `src/components/common/Modal.tsx`:
      - 背景暗転のオーバーレイ + 白い矩形コンテナ
      - title / children / onClose / isOpen を Props に持つ
      - ESC キーで閉じる
    - `src/components/common/ConfirmDialog.tsx`:
      - Modal を内部利用し、メッセージ + 「はい」「いいえ」ボタンを配置
      - onConfirm / onCancel コールバック
    - `src/components/common/Notification.tsx`:
      - 画面上部に表示するトーストバー
      - type（success=緑 / error=赤）に応じたスタイル
      - 自動消去（数秒後にフェードアウト）
    - `src/components/common/index.ts` にバレルエクスポート追加
  - **完了条件**:
    - Modal が開閉でき、背景暗転が適用される
    - ConfirmDialog が「はい」「いいえ」で正しくコールバックを呼ぶ
    - Notification が表示後に自動消去される
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §4.1 共通コンポーネント
  - **推定時間**: 1時間30分

---

- [ ] **Task 1.6**: NotificationContext の作成

  - **目的**: 画面をまたいでトースト通知を表示する仕組みを React Context で実装し、
    学習テーマ「React Context」を実践する
  - **やること**:
    - `src/contexts/NotificationContext.tsx` に以下を実装:
      - `NotificationProvider` コンポーネント
      - `useNotification` カスタムフック
      - `showSuccess(message: string)` / `showError(message: string)` 関数
      - 通知リスト（id, type, message）の state 管理
      - 自動消去タイマー（3〜5秒で非表示）
    - `App.tsx` の最上位に `NotificationProvider` をラップ
    - Notification コンポーネントを Provider 内で描画
  - **完了条件**:
    - 任意の Feature コンポーネントから `useNotification()` で通知を表示できる
    - 成功（緑）とエラー（赤）の通知が画面上部に表示される
    - 一定時間後に自動で消える
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §7 Context・グローバル状態
  - **依存**: Task 1.5（Notification コンポーネント）
  - **推定時間**: 1時間

---

- [ ] **Task 1.7**: レイアウトコンポーネントの作成

  - **目的**: 全画面共通のヘッダー・ナビゲーション・レイアウト枠を実装し、
    ページ間遷移のナビゲーションを提供する
  - **やること**:
    - `src/components/layout/Header.tsx`:
      - アプリ名「領収書 OCR」の表示
      - 3画面へのナビゲーションリンク（アップロード / 処理状況 / データ一覧）
      - アクティブ画面のハイライト表示（React Router の `useLocation` / `NavLink`）
    - `src/components/layout/Layout.tsx`:
      - Header を含むページ全体のレイアウト
      - `max-width: 1280px` で中央揃え
      - `<Outlet />` で子ルートを描画
    - `src/components/layout/index.ts` にバレルエクスポート
  - **完了条件**:
    - ヘッダーに3つのナビゲーションリンクが表示される
    - 現在のページに応じてリンクがハイライトされる
    - コンテンツ領域が中央揃えで max-width 制限される
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §4.2 レイアウト
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（Header デザイン）
  - **推定時間**: 45分

---

- [ ] **Task 1.8**: ルーティング設定

  - **目的**: 3画面のルーティングを定義し、`/` から `/upload` へのリダイレクトを設定する
  - **やること**:
    - `src/App.tsx` を更新:
      - Layout コンポーネントを親ルートに設定
      - `/upload` → UploadPage（画面A）
      - `/batch/:batchId` → OcrJobPage（画面B）
      - `/receipts` → ReceiptListPage（画面C）
      - `/` → `/upload` にリダイレクト
    - ページコンポーネントの仮実装（プレースホルダー）:
      - `src/pages/UploadPage.tsx`
      - `src/pages/OcrJobPage.tsx`
      - `src/pages/ReceiptListPage.tsx`
      - `src/pages/index.ts`
    - NotificationProvider を Router の外側にラップ
  - **完了条件**:
    - 各 URL にアクセスするとプレースホルダーページが表示される
    - `/` にアクセスすると `/upload` にリダイレクトされる
    - ヘッダーのナビゲーションで画面遷移ができる
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §6 ページコンポーネント
    - `frontend/src/App.tsx`（既存コード、更新対象）
  - **依存**: Task 1.6, Task 1.7
  - **推定時間**: 30分

---

- [ ] **Task 1.9**: 共通ポーリングフックの作成

  - **目的**: 画面B のバッチステータスポーリングと画面C のエクスポートステータスポーリングで
    共通利用する汎用ポーリングフックを作成する
  - **やること**:
    - `src/hooks/usePolling.ts` に以下を実装:
      - `usePolling<T>(options)` の引数:
        - `queryFn: () => Promise<T>` — ポーリングで呼び出す関数
        - `interval: number` — ポーリング間隔（ms）
        - `enabled: boolean` — ポーリングの有効/無効
        - `shouldStop: (data: T) => boolean` — 停止条件判定関数
      - 戻り値: `{ data, isLoading, error, isPolling }`
      - `enabled` が true の間、interval ミリ秒ごとに queryFn を呼び出す
      - `shouldStop` が true を返したらポーリング停止
      - コンポーネントのアンマウント時にクリーンアップ
    - `src/hooks/index.ts` にバレルエクスポート
  - **完了条件**:
    - ポーリングが指定間隔で実行される
    - shouldStop 条件を満たすとポーリングが停止する
    - コンポーネントのアンマウント時にタイマーがクリアされる
    - 単体テストが存在し、Vitest でパスする
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §8.1 共通フック
    - `@docs/2.基本設計/API設計書.md` の §6 ポーリング設計
  - **推定時間**: 1時間

---

### Phase 2: アップロード機能（画面A）

**この Phase のゴール**:
ファイルをドラッグ＆ドロップまたはボタンで選択し、バリデーション後にアップロードを実行して
画面B へ遷移できる状態にする。

**共通参照**:

- `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.1 upload
- `@docs/2.基本設計/API設計書.md` の §5.1 アップロードバッチ
- `@docs/3.詳細設計/シーケンス図.md` の §2.1 アップロードシーケンス

---

- [ ] **Task 2.1**: upload Feature の型定義

  - **目的**: アップロード Feature で使用する型（ファイル選択状態・API レスポンス）を定義する
  - **やること**:
    - `src/features/upload/types/index.ts` に以下を定義:
      - `SelectedFile` 型（file, id, validationError?）
      - `CreateBatchResponse` 型（batchId, jobs[], totalFiles, createdAt）
      - `BatchJob` 型（jobId, fileName, fileSize, mimeType, status, createdAt）
  - **完了条件**:
    - 型定義がエクスポートされ、TypeScript の型チェックが通る
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.1 POST /api/upload-batches レスポンス
  - **推定時間**: 15分

---

- [ ] **Task 2.2**: upload API サービスの作成

  - **目的**: アップロードバッチ作成 API の呼び出し関数を実装する
  - **やること**:
    - `src/features/upload/api/uploadService.ts` に以下を実装:
      - `createBatch(files: File[]): Promise<CreateBatchResponse>`
      - Content-Type を `multipart/form-data` に設定
      - Axios インスタンス（`lib/axios.ts`）を使用
  - **完了条件**:
    - `createBatch` が `POST /api/upload-batches` に multipart/form-data で送信する
    - レスポンスが `CreateBatchResponse` 型にマッピングされる
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.1 POST /api/upload-batches
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.1 API サービス
  - **推定時間**: 30分

---

- [ ] **Task 2.3**: useFileSelection フックの作成

  - **目的**: ファイルの追加・削除・バリデーションのロジックをフックに集約し、
    UI コンポーネントからロジックを分離する
  - **やること**:
    - `src/features/upload/hooks/useFileSelection.ts` に以下を実装:
      - `addFiles(files: FileList | File[])` — ファイル追加 + バリデーション
      - `removeFile(fileId: string)` — ファイル削除
      - `clearFiles()` — 全ファイルクリア
      - 戻り値: `{ files, addFiles, removeFile, clearFiles, hasErrors, canUpload }`
      - バリデーション:
        - ファイル形式チェック（JPEG, PNG, PDF のみ）
        - ファイルサイズチェック（10MB 以下）
        - ファイル枚数チェック（10枚以下）
      - バリデーションエラーは各ファイルの `validationError` に格納
    - `src/features/upload/hooks/index.ts` にバレルエクスポート
  - **完了条件**:
    - 不正なファイルを追加するとバリデーションエラーが設定される
    - 正しいファイルのみの場合 `canUpload` が true になる
    - 単体テストが存在し、Vitest でパスする
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.1 カスタムフック
    - `src/utils/validation.ts`（Task 1.2 で作成）
  - **依存**: Task 1.2（validation ユーティリティ）
  - **推定時間**: 1時間

---

- [ ] **Task 2.4**: useUpload フックの作成

  - **目的**: アップロード実行のロジック（API 呼び出し・画面遷移・通知）をフックに集約する
  - **やること**:
    - `src/features/upload/hooks/useUpload.ts` に以下を実装:
      - `upload(files: File[])` — createBatch API を呼び出し
      - 成功時: `navigate(/batch/{batchId})` で画面B に遷移
      - 失敗時: NotificationContext 経由でエラー表示
      - API エラーコードに応じた分岐:
        - `FILE_TOO_LARGE` / `FILE_TYPE_NOT_ALLOWED` は該当ファイルにエラー紐付け
        - `TOO_MANY_FILES` は UploadButton を無効化状態にする
      - ローディング状態の管理（isUploading）
    - `src/features/upload/hooks/index.ts` にエクスポート追加
  - **完了条件**:
    - アップロード成功後に `/batch/{batchId}` に遷移する
    - アップロード中に isUploading が true になる
    - API エラー時に通知が表示される
    - `FILE_TOO_LARGE` / `FILE_TYPE_NOT_ALLOWED` / `TOO_MANY_FILES` が設計どおりに UI 反映される
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.1 カスタムフック
    - `@docs/3.詳細設計/シーケンス図.md` の §2.1（アップロード後の遷移）
  - **依存**: Task 2.2（uploadService）, Task 1.6（NotificationContext）
  - **推定時間**: 30分

---

- [ ] **Task 2.5**: DropZone コンポーネントの作成

  - **目的**: ドラッグ＆ドロップ対応のファイル選択エリアを実装する
  - **やること**:
    - `src/features/upload/components/DropZone.tsx` に以下を実装:
      - `<input type="file" multiple accept="..." />` を内包
      - ドラッグ＆ドロップイベント（dragEnter, dragLeave, dragOver, drop）
      - ドラッグ中のビジュアルフィードバック（ボーダー色変更等）
      - 「ファイルをドラッグ＆ドロップ または」テキスト + 「ファイルを選択」ボタン
      - ファイル選択時に `onFilesSelected(files)` コールバックを呼び出す
    - Tailwind CSS でスタイリング
  - **完了条件**:
    - ファイルをドラッグ＆ドロップで追加できる
    - 「ファイルを選択」ボタンクリックでファイルダイアログが開く
    - ドラッグ中にエリアのスタイルが変化する
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.1 コンポーネント一覧
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（DropZone デザイン）
  - **推定時間**: 1時間

---

- [ ] **Task 2.6**: FileList / FileRow コンポーネントの作成

  - **目的**: 選択済みファイルの一覧表示とファイルごとの操作 UI を実装する
  - **やること**:
    - `src/features/upload/components/FileRow.tsx`:
      - ファイル名、サイズ（formatFileSize）、形式アイコン、削除ボタン
      - バリデーションエラー時のエラーメッセージ表示（赤字）
    - `src/features/upload/components/FileList.tsx`:
      - FileRow の一覧表示
      - ファイル未選択時はファイル制約の案内表示
        （対応形式: JPEG, PNG, PDF / 最大サイズ: 10MB / 最大枚数: 10枚）
    - `src/features/upload/components/index.ts` にバレルエクスポート
  - **完了条件**:
    - 選択済みファイルが一覧表示される
    - 各ファイルの削除ボタンが動作する
    - バリデーションエラーがファイル行に赤字で表示される
    - ファイルサイズが `2.0 MB` 形式でフォーマットされている
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.1 コンポーネント一覧
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（FileList, FileRow デザイン）
  - **依存**: Task 1.2（format ユーティリティ）
  - **推定時間**: 1時間

---

- [ ] **Task 2.7**: UploadButton コンポーネントの作成

  - **目的**: アップロード実行ボタンを実装する
  - **やること**:
    - `src/features/upload/components/UploadButton.tsx`:
      - ファイル数を含むラベル（「3枚をアップロード」）
      - ファイル未選択 or バリデーションエラー時は disabled
      - アップロード中はローディングスピナー表示
      - onClick で useUpload の upload 関数を呼び出す
    - `src/features/upload/components/index.ts` にエクスポート追加
  - **完了条件**:
    - ファイル選択数に応じてボタンラベルが変わる
    - 無効状態と有効状態が正しく切り替わる
    - アップロード中にスピナーが表示される
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.1 コンポーネント一覧
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（UploadButton デザイン）
  - **依存**: Task 1.4（Button / Spinner）
  - **推定時間**: 30分

---

- [ ] **Task 2.8**: UploadPage の組み立て

  - **目的**: 画面A のページコンポーネントを実装し、Feature コンポーネントを組み合わせる
  - **やること**:
    - `src/pages/UploadPage.tsx` を更新:
      - useFileSelection フックの初期化
      - DropZone → FileList → UploadButton の配置
      - 各コンポーネントにフックの状態を Props として渡す
  - **完了条件**:
    - ファイル選択 → バリデーション → アップロード → 画面B 遷移の一連のフローが動作する
    - エラーがない状態でアップロードできる
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §6 ページコンポーネント
    - `@docs/3.詳細設計/シーケンス図.md` の §2.1（フロントエンド部分）
  - **依存**: Task 2.3〜2.7
  - **推定時間**: 30分

---

### Phase 3: OCR ジョブ処理・確認機能（画面B）

**この Phase のゴール**:
アップロード後のバッチ内ジョブの処理進捗をポーリングで表示し、
COMPLETED ジョブの OCR 結果を画像プレビューと共に確認・編集・確定できる状態にする。
また、FAILED ジョブの手動リトライを実行できるようにする。

**共通参照**:

- `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.2 ocrjob
- `@docs/2.基本設計/API設計書.md` の §5.2 OCR ジョブ
- `@docs/3.詳細設計/シーケンス図.md` の §2, §3（OCR・確認フロー）

**重要な設計決定**:

- ポーリング間隔は2秒、全ジョブが終端ステータス（COMPLETED / CONFIRMED / FAILED）に到達したら停止
- 署名付き URL の有効期限は15分。PDF の場合は変換済み画像の URL を返す

---

- [ ] **Task 3.1**: ocrjob Feature の型定義

  - **目的**: OCR ジョブ Feature で使用する型（ステータス、バッチ情報、ジョブ詳細）を定義する
  - **やること**:
    - `src/features/ocrjob/types/index.ts` に以下を定義:
      - `OcrJobStatus` 型（`'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'CONFIRMED' | 'FAILED'`）
      - `BatchStatusResponse` 型（batchId, jobs[], summary, createdAt）
      - `BatchSummary` 型（total, queued, processing, completed, confirmed, failed）
      - `BatchJob` 型（jobId, fileName, status, retryCount, createdAt, completedAt）
      - `OcrJobDetail` 型（jobId, batchId, fileName, fileSize, mimeType, status, retryCount, ocrResult, errorMessage, createdAt, completedAt）
      - `OcrResult` 型（date, amount, storeName, description, taxCategory, rawText）
      - `ImageUrlResponse` 型（imageUrl, expiresAt）
      - `ConfirmRequest` 型（date, amount, storeName, description?, taxCategory?）
      - `ConfirmResponse` 型（receiptId, jobId, date, amount, storeName, description, taxCategory, confirmedAt）
      - `RetryResponse` 型（jobId, status, retryCount, message）
  - **完了条件**:
    - すべての型がエクスポートされ、TypeScript の型チェックが通る
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §3.1 ステータス定義, §5.1〜§5.2 レスポンス形式
  - **推定時間**: 30分

---

- [ ] **Task 3.2**: ocrjob API サービスの作成

  - **目的**: OCR ジョブ関連の全 API 呼び出し関数を実装する
  - **やること**:
    - `src/features/ocrjob/api/ocrJobService.ts` に以下を実装:
      - `fetchBatchStatus(batchId: string): Promise<BatchStatusResponse>`
      - `fetchOcrJob(jobId: string): Promise<OcrJobDetail>`
      - `fetchImageUrl(jobId: string): Promise<ImageUrlResponse>`
      - `confirmJob(jobId: string, data: ConfirmRequest): Promise<ConfirmResponse>`
      - `retryJob(jobId: string): Promise<RetryResponse>`
    - 各関数は Axios インスタンスを使用
  - **完了条件**:
    - 5つの API 呼び出し関数がエクスポートされている
    - 各関数が正しい HTTP メソッドとパスで API を呼び出す
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.1〜§5.2
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.2 API サービス
  - **依存**: Task 3.1（型定義）
  - **推定時間**: 30分

---

- [ ] **Task 3.3**: useBatchStatus フックの作成

  - **目的**: バッチのステータスを2秒間隔でポーリングし、処理進捗をリアルタイムに追跡する
  - **やること**:
    - `src/features/ocrjob/hooks/useBatchStatus.ts` に以下を実装:
      - `useBatchStatus(batchId: string)` を定義
      - 内部で `usePolling` を使用（interval: 2000ms）
      - 停止条件: `summary.queued + summary.processing === 0`
      - 戻り値: `{ batchStatus, isPolling, error }`
    - `src/features/ocrjob/hooks/index.ts` にバレルエクスポート
  - **完了条件**:
    - batchId を渡すと2秒間隔でバッチステータスがポーリングされる
    - 全ジョブが終端ステータスに到達するとポーリングが停止する
    - ポーリング中のデータが正しく state に反映される
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §6.1 ポーリング設計
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.2 カスタムフック
  - **依存**: Task 1.9（usePolling）, Task 3.2（ocrJobService）
  - **推定時間**: 45分

---

- [ ] **Task 3.4**: useOcrJob フックの作成

  - **目的**: ジョブ詳細と画像 URL を並行取得し、確認・編集画面のデータを提供する
  - **やること**:
    - `src/features/ocrjob/hooks/useOcrJob.ts` に以下を実装:
      - `useOcrJob()` を定義
      - `fetchJob(jobId: string)` — `fetchOcrJob` と `fetchImageUrl` を `Promise.all` で並行呼び出し
      - 戻り値: `{ ocrJob, imageUrl, isLoading, error, fetchJob }`
      - ジョブ選択時に fetchJob を呼び出す
    - `src/features/ocrjob/hooks/index.ts` にエクスポート追加
  - **完了条件**:
    - fetchJob 呼び出しでジョブ詳細と画像 URL が並行取得される
    - ローディング状態が正しく管理される
  - **参照**:
    - `@docs/3.詳細設計/シーケンス図.md` の §3（確認・確定シーケンス、par ブロック）
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.2 カスタムフック
  - **依存**: Task 3.2（ocrJobService）
  - **推定時間**: 30分

---

- [ ] **Task 3.5**: useConfirmJob / useRetryJob フックの作成

  - **目的**: 確定操作とリトライ操作のロジックをフックに集約する
  - **やること**:
    - `src/features/ocrjob/hooks/useConfirmJob.ts`:
      - `confirmJob(jobId, data)` — confirmJob API を呼び出し
      - 成功時: NotificationContext で成功通知 + バッチステータスを再取得
      - 失敗時: エラーコードに応じたエラーハンドリング
        - `JOB_NOT_RETRYABLE` → 「このジョブは操作できません」通知 + ジョブ一覧再取得
        - `VALIDATION_ERROR` → フィールドエラーを返却
      - 戻り値: `{ confirm, isConfirming, fieldErrors }`
    - `src/features/ocrjob/hooks/useRetryJob.ts`:
      - `retryJob(jobId)` — retryJob API を呼び出し
      - 成功時: 成功通知 + ポーリング再開のトリガー
      - 失敗時: `JOB_NOT_RETRYABLE` → エラー通知
      - 戻り値: `{ retry, isRetrying }`
    - `src/features/ocrjob/hooks/index.ts` にエクスポート追加
  - **完了条件**:
    - 確定成功後にジョブステータスが CONFIRMED に更新される
    - リトライ成功後にジョブステータスが QUEUED に戻りポーリングが再開される
    - エラーコードに応じた適切なフィードバックが表示される
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §7.2 ocrjob エラー処理
    - `@docs/3.詳細設計/シーケンス図.md` の §3（確定）, §2.3（手動リトライ）
  - **依存**: Task 3.2（ocrJobService）, Task 1.6（NotificationContext）
  - **推定時間**: 1時間

---

- [ ] **Task 3.6**: ProgressSection / StatusBadge コンポーネントの作成

  - **目的**: バッチ内ジョブの処理進捗をプログレスバーとステータスバッジで視覚的に表示する
  - **やること**:
    - `src/features/ocrjob/components/StatusBadge.tsx`:
      - ステータスに応じた色付きバッジ:
        - QUEUED → グレー「待機中」
        - PROCESSING → 青「処理中」
        - COMPLETED → 緑「完了」
        - CONFIRMED → 紫「確定済」
        - FAILED → 赤「失敗」
    - `src/features/ocrjob/components/ProgressSection.tsx`:
      - プログレスバー（完了 + 確定済 / 全体の割合）
      - ステータス別件数の StatusBadge × 5 を並列表示
    - `src/features/ocrjob/components/index.ts` にバレルエクスポート
  - **完了条件**:
    - プログレスバーが進捗に応じて伸びる
    - 各ステータスのバッジが正しい色とラベルで表示される
    - ポーリングで取得した summary の値が反映される
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.2 コンポーネント一覧
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（ProgressSection, StatusBadges デザイン）
  - **推定時間**: 1時間

---

- [ ] **Task 3.7**: JobList / JobRow コンポーネントの作成

  - **目的**: バッチ内のジョブ一覧を表示し、ステータスに応じた操作 UI を提供する
  - **やること**:
    - `src/features/ocrjob/components/JobRow.tsx`:
      - ファイル名、ステータスバッジ、作成日時を表示
      - ステータスに応じた右側の表示切り替え:
        - QUEUED → 「待機中」テキスト
        - PROCESSING → Spinner
        - COMPLETED → 「確認」ボタン（クリックで EditSection 表示）
        - CONFIRMED → チェックアイコン + 「確定済」テキスト
        - FAILED → エラーメッセージ + 「再試行」ボタン
    - `src/features/ocrjob/components/JobList.tsx`:
      - ヘッダー行（ファイル名 / ステータス / アクション）
      - JobRow の一覧表示
    - `src/features/ocrjob/components/index.ts` にエクスポート追加
  - **完了条件**:
    - 全ステータスのジョブ行が正しく表示される
    - COMPLETED ジョブの「確認」ボタンがクリック可能
    - FAILED ジョブの「再試行」ボタンが動作する
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.2 コンポーネント一覧
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（JobList, Job-* デザイン）
  - **依存**: Task 3.6（StatusBadge）, Task 1.4（Spinner）
  - **推定時間**: 1時間30分

---

- [ ] **Task 3.8**: EditSection / ImagePreview / OcrRawText コンポーネントの作成

  - **目的**: OCR 結果の確認・編集エリアを実装する（画像プレビュー + フォーム + 生テキスト）
  - **やること**:
    - `src/features/ocrjob/components/ImagePreview.tsx`:
      - 署名付き URL で取得した画像を `<img>` タグで表示
      - 画像読み込み中は Spinner を表示
      - エラー時は代替テキスト表示
    - `src/features/ocrjob/components/OcrRawText.tsx`:
      - 「OCR 生テキスト」のトグル表示セクション
      - クリックで展開/折りたたみ
      - 読み取り専用の `<pre>` テキスト表示
    - `src/features/ocrjob/components/EditSection.tsx`:
      - 2カラムレイアウト（左: ImagePreview、右: ConfirmForm + OcrRawText）
      - タイトル「確認・編集」の表示
    - `src/features/ocrjob/components/index.ts` にエクスポート追加
  - **完了条件**:
    - 画像プレビューが署名付き URL から読み込まれて表示される
    - OCR 生テキストがトグルで開閉できる
    - 2カラムレイアウトが正しく配置される
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.2 コンポーネント一覧
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（EditSection, ImagePreview, OcrRawText デザイン）
  - **依存**: Task 1.4（Spinner）
  - **推定時間**: 1時間

---

- [ ] **Task 3.9**: ConfirmForm コンポーネントの作成

  - **目的**: OCR 読み取り結果を編集して確定するフォームを実装する
  - **やること**:
    - `src/features/ocrjob/components/ConfirmForm.tsx`:
      - 5フィールドの入力フォーム:
        - 日付（date, type="date", 必須）
        - 金額（amount, type="number", 必須, 1以上）
        - 支払先（storeName, type="text", 必須, 200文字以内）
        - 品目（description, type="text", 任意, 500文字以内）
        - 税区分（taxCategory, type="text", 任意）
      - OCR 結果を初期値としてセット
      - フロントエンドバリデーション（必須チェック、範囲チェック）
      - フィールドごとのエラーメッセージ表示
      - 「確定」ボタン（ローディング状態対応）
      - サーバーサイドのバリデーションエラー（fieldErrors）の表示
    - `src/features/ocrjob/components/index.ts` にエクスポート追加
  - **完了条件**:
    - OCR 結果が初期値としてフォームに表示される
    - 必須フィールドが空の状態で確定するとエラーが表示される
    - 確定成功後にフォームがリセットされる（次のジョブ選択に備える）
    - サーバーサイドのフィールドエラーが対応するフィールドに表示される
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.2 POST /api/ocr-jobs/{jobId}/confirm リクエスト
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §7.2 ocrjob エラー処理
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（FormArea デザイン）
  - **依存**: Task 3.5（useConfirmJob）, Task 1.4（Button）
  - **推定時間**: 1時間30分

---

- [ ] **Task 3.10**: OcrJobPage の組み立て

  - **目的**: 画面B のページコンポーネントを実装し、Feature コンポーネントを組み合わせる
  - **やること**:
    - `src/pages/OcrJobPage.tsx` を更新:
      - URL パラメータから batchId を取得（`useParams`）
      - useBatchStatus でポーリング開始
      - 選択中ジョブの state 管理（selectedJobId）
      - ジョブ選択時に useOcrJob で詳細取得
      - ProgressSection + JobList + EditSection（条件付き表示）の配置
      - 全ジョブが CONFIRMED 到達時に完了メッセージを表示
      - 「データ一覧へ」リンクボタン（`/receipts`）を表示
      - batchId が不正な場合のエラーハンドリング
  - **完了条件**:
    - アップロード後に画面B に遷移し、ポーリングが開始される
    - ジョブの処理進捗がリアルタイムに更新される
    - COMPLETED ジョブをクリックすると確認・編集エリアが表示される
    - 確定操作が正常に動作し、ステータスが CONFIRMED に更新される
    - FAILED ジョブの「再試行」が動作する
    - 全ジョブ確定済み時に完了メッセージと `/receipts` 導線が表示される
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §6 ページコンポーネント
    - `@docs/3.詳細設計/シーケンス図.md` の §2, §3
  - **依存**: Task 3.3〜3.9
  - **推定時間**: 1時間

---

### Phase 4: レシート一覧・エクスポート機能（画面C）

**この Phase のゴール**:
確定済みレシートをテーブル形式で一覧表示し、フィルタ・ソート・ページングで閲覧、
編集・削除の CRUD 操作、チェックボックスで選択して CSV エクスポートが完了する状態にする。

**共通参照**:

- `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.3 receipt
- `@docs/2.基本設計/API設計書.md` の §5.3 レシート, §5.4 エクスポート
- `@docs/3.詳細設計/シーケンス図.md` の §4

---

- [ ] **Task 4.1**: receipt Feature の型定義

  - **目的**: レシート・エクスポート Feature で使用する型を定義する
  - **やること**:
    - `src/features/receipt/types/index.ts` に以下を定義:
      - `Receipt` 型（receiptId, date, amount, storeName, description, taxCategory, confirmedAt, updatedAt）
      - `ReceiptListResponse` 型（`PageResponse<Receipt>` を利用）
      - `ReceiptSearchParams` 型（page, size, sort, order, dateFrom?, dateTo?, storeName?, amountMin?, amountMax?）
      - `UpdateReceiptRequest` 型（date, amount, storeName, description?, taxCategory?）
      - `ExportJobStatus` 型（`'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'`）
      - `CreateExportRequest` 型（receiptIds: string[]）
      - `CreateExportResponse` 型（exportId, status, totalReceipts, createdAt）
      - `ExportStatusResponse` 型（exportId, status, totalReceipts, fileName, createdAt, completedAt）
  - **完了条件**:
    - すべての型がエクスポートされ、TypeScript の型チェックが通る
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.3〜§5.4 レスポンス形式
  - **推定時間**: 30分

---

- [ ] **Task 4.2**: receipt API サービスの作成

  - **目的**: レシート CRUD の API 呼び出し関数を実装する
  - **やること**:
    - `src/features/receipt/api/receiptService.ts` に以下を実装:
      - `fetchReceipts(params: ReceiptSearchParams): Promise<ReceiptListResponse>`
        — クエリパラメータでフィルタ・ソート・ページングを渡す
      - `fetchReceipt(receiptId: string): Promise<Receipt>`
      - `updateReceipt(receiptId: string, data: UpdateReceiptRequest): Promise<Receipt>`
      - `deleteReceipt(receiptId: string): Promise<void>`
  - **完了条件**:
    - 4つの API 呼び出し関数がエクスポートされている
    - GET /api/receipts のクエリパラメータが正しく付与される
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.3 レシート
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.3 API サービス
  - **依存**: Task 4.1（型定義）
  - **推定時間**: 30分

---

- [ ] **Task 4.3**: export API サービスの作成

  - **目的**: エクスポートジョブの作成・ステータス取得・ダウンロードの API 呼び出し関数を実装する
  - **やること**:
    - `src/features/receipt/api/exportService.ts` に以下を実装:
      - `createExport(receiptIds: string[]): Promise<CreateExportResponse>`
      - `fetchExportStatus(exportId: string): Promise<ExportStatusResponse>`
      - `downloadExport(exportId: string): Promise<Blob>`
        — `responseType: 'blob'` で CSV を取得
  - **完了条件**:
    - 3つの API 呼び出し関数がエクスポートされている
    - downloadExport が Blob データを返す
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.4 エクスポート
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.3 API サービス
  - **依存**: Task 4.1（型定義）
  - **推定時間**: 30分

---

- [ ] **Task 4.4**: useReceipts フックの作成

  - **目的**: レシート一覧の取得・フィルタ・ソート・ページング状態を管理するフックを実装する
  - **やること**:
    - `src/features/receipt/hooks/useReceipts.ts` に以下を実装:
      - 検索パラメータの state 管理（page, size, sort, order, filters）
      - `fetchReceipts` を呼び出してデータ取得
      - `setFilters(filters)` — フィルタ条件の更新（page を 0 にリセット）
      - `setSort(sort, order)` — ソート条件の更新
      - `setPage(page)` — ページ変更
      - `refresh()` — 現在の条件で再取得
      - 戻り値: `{ receipts, page, isLoading, error, setFilters, setSort, setPage, refresh }`
    - `src/features/receipt/hooks/index.ts` にバレルエクスポート
  - **完了条件**:
    - 初期表示時にデフォルト条件（page=0, size=20, sort=date, order=desc）でデータ取得される
    - フィルタ変更時にページが 0 にリセットされて再取得される
    - ソート・ページ変更が正しく動作する
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.3 GET /api/receipts クエリパラメータ
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.3 カスタムフック
  - **依存**: Task 4.2（receiptService）
  - **推定時間**: 1時間

---

- [ ] **Task 4.5**: useReceiptSelection フックの作成

  - **目的**: チェックボックスの選択状態を管理し、エクスポート対象のレシート ID を提供する
  - **やること**:
    - `src/features/receipt/hooks/useReceiptSelection.ts` に以下を実装:
      - `toggleSelect(receiptId: string)` — 個別選択/解除
      - `toggleSelectAll(receiptIds: string[])` — 全選択/全解除
      - `clearSelection()` — 選択をクリア
      - 戻り値: `{ selectedIds, toggleSelect, toggleSelectAll, clearSelection, selectedCount, isAllSelected }`
    - ページ遷移時に選択をクリアする
    - `src/features/receipt/hooks/index.ts` にエクスポート追加
  - **完了条件**:
    - 個別チェックボックスで選択/解除ができる
    - ヘッダーのチェックボックスで全選択/全解除ができる
    - selectedCount が正しくカウントされる
    - ページ変更時に選択がクリアされる
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.3 カスタムフック
  - **推定時間**: 45分

---

- [ ] **Task 4.6**: useUpdateReceipt / useDeleteReceipt フックの作成

  - **目的**: レシートの編集・削除操作のロジックをフックに集約する
  - **やること**:
    - `src/features/receipt/hooks/useUpdateReceipt.ts`:
      - `updateReceipt(receiptId, data)` — updateReceipt API を呼び出し
      - 成功時: 成功通知 + 一覧の再取得
      - 失敗時: VALIDATION_ERROR → フィールドエラーを返却
      - 戻り値: `{ update, isUpdating, fieldErrors }`
    - `src/features/receipt/hooks/useDeleteReceipt.ts`:
      - `deleteReceipt(receiptId)` — deleteReceipt API を呼び出し
      - 成功時: 成功通知 + 一覧の再取得
      - 削除確認ダイアログの表示制御（confirmTarget state）
      - 戻り値: `{ requestDelete, confirmDelete, cancelDelete, isDeleting, confirmTarget }`
    - `src/features/receipt/hooks/index.ts` にエクスポート追加
  - **完了条件**:
    - レシート更新成功後に一覧が再取得される
    - レシート削除時に確認ダイアログが表示され、確認後に削除される
    - バリデーションエラーがフィールドに表示される
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §7.2 receipt エラー処理
    - `@docs/3.詳細設計/シーケンス図.md` の §4.2（編集・削除シーケンス）
  - **依存**: Task 4.2（receiptService）, Task 1.6（NotificationContext）
  - **推定時間**: 1時間

---

- [ ] **Task 4.7**: useExport フックの作成

  - **目的**: CSV エクスポートの一連のフロー（ジョブ作成 → ポーリング → ダウンロード）を管理する
  - **やること**:
    - `src/features/receipt/hooks/useExport.ts` に以下を実装:
      - `startExport(receiptIds: string[])`:
        1. `createExport(receiptIds)` で exportId を取得
        2. `usePolling` で2秒間隔のポーリング開始（fetchExportStatus）
        3. COMPLETED → `downloadExport(exportId)` で Blob 取得 → ブラウザダウンロード
        4. FAILED → エラー通知
      - `downloadExport` が `409 EXPORT_NOT_READY` の場合はエラー通知せずポーリング継続
      - ブラウザダウンロードの実装:
        - `URL.createObjectURL(blob)` で URL 生成
        - `<a download="filename.csv">` を動的生成してクリック
        - `URL.revokeObjectURL` でメモリ解放
      - 戻り値: `{ startExport, isExporting, exportStatus }`
    - `src/features/receipt/hooks/index.ts` にエクスポート追加
  - **完了条件**:
    - エクスポートボタン押下 → ジョブ作成 → ポーリング → 自動ダウンロードの一連のフローが動作する
    - ダウンロード成功後に成功通知が表示される
    - ダウンロード失敗時にエラー通知が表示される
    - `EXPORT_NOT_READY` では通知せずにポーリングを継続し、完了時に正常ダウンロードされる
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §6.2 エクスポートステータスのポーリング
    - `@docs/3.詳細設計/シーケンス図.md` の §4.1（エクスポートシーケンス）
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.3 カスタムフック
  - **依存**: Task 4.3（exportService）, Task 1.9（usePolling）, Task 1.6（NotificationContext）
  - **推定時間**: 1時間30分

---

- [ ] **Task 4.8**: FilterBar コンポーネントの作成

  - **目的**: レシート一覧の検索条件を入力する UI を実装する
  - **やること**:
    - `src/features/receipt/components/FilterBar.tsx`:
      - 5つのフィルタ入力フィールド:
        - 期間（開始日 type="date" + 終了日 type="date"）
        - 支払先（type="text", 部分一致）
        - 金額（下限 type="number" + 上限 type="number"）
      - 「検索」ボタン（onSearch コールバック）
      - 「クリア」ボタン（フィルタを初期化）
      - Tailwind CSS でフレックスレイアウト
    - `src/features/receipt/components/index.ts` にバレルエクスポート
  - **完了条件**:
    - フィルタ条件を入力して「検索」で一覧が絞り込まれる
    - 「クリア」で全フィルタがリセットされる
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.3 GET /api/receipts クエリパラメータ
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（FilterBar デザイン）
  - **推定時間**: 1時間

---

- [ ] **Task 4.9**: ReceiptTable / ReceiptRow コンポーネントの作成

  - **目的**: レシートのテーブル表示を実装する（ソート・チェックボックス対応）
  - **やること**:
    - `src/features/receipt/components/ReceiptRow.tsx`:
      - チェックボックス + データ列（日付・金額・支払先・品目・税区分・確定日時）
      - 「編集」ボタン + 「削除」ボタン
      - 金額は `formatCurrency` でフォーマット
    - `src/features/receipt/components/ReceiptTable.tsx`:
      - テーブルヘッダー（全選択チェックボックス + カラム名）
      - ソート可能なカラムヘッダー（クリックでソート順切り替え、矢印アイコン）
      - ReceiptRow の一覧表示
      - データなし時のメッセージ表示
    - `src/features/receipt/components/index.ts` にエクスポート追加
  - **完了条件**:
    - レシートデータがテーブル形式で表示される
    - カラムヘッダークリックでソートが切り替わる
    - チェックボックスで個別選択・全選択ができる
    - 金額が `¥3,980` 形式でフォーマットされている
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.3 コンポーネント一覧
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（DataTable, DataRow デザイン）
  - **依存**: Task 1.2（format ユーティリティ）
  - **推定時間**: 1時間30分

---

- [ ] **Task 4.10**: Pagination コンポーネントの作成

  - **目的**: テーブルのページ送り UI を実装する
  - **やること**:
    - `src/features/receipt/components/Pagination.tsx`:
      - 「前へ」「次へ」ボタン
      - ページ番号の表示（現在ページ / 総ページ数）
      - 全件数の表示（「全42件」）
      - 先頭/末尾ページでボタン無効化
      - onPageChange コールバック
    - `src/features/receipt/components/index.ts` にエクスポート追加
  - **完了条件**:
    - ページ番号と全件数が正しく表示される
    - 「前へ」「次へ」でページが切り替わる
    - 先頭ページで「前へ」が無効になる
    - 末尾ページで「次へ」が無効になる
  - **参照**:
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（Pagination デザイン）
  - **推定時間**: 45分

---

- [ ] **Task 4.11**: ExportActionBar コンポーネントの作成

  - **目的**: 選択件数の表示と CSV エクスポートボタンを実装する
  - **やること**:
    - `src/features/receipt/components/ExportActionBar.tsx`:
      - 選択件数の表示（「3件選択中」）
      - 「CSV エクスポート」ボタン
      - 未選択時はボタン無効
      - エクスポート中はローディングスピナー表示
      - onClick で useExport の startExport を呼び出す
    - `src/features/receipt/components/index.ts` にエクスポート追加
  - **完了条件**:
    - 選択件数が正しく表示される
    - 未選択時にボタンが無効になる
    - エクスポート中にスピナーが表示される
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §5.3 コンポーネント一覧
    - `@docs/2.基本設計/領収書OCR画面デザイン.pen`（ExportActionBar デザイン）
  - **依存**: Task 1.4（Button / Spinner）
  - **推定時間**: 30分

---

- [ ] **Task 4.12**: EditReceiptModal / DeleteConfirmDialog コンポーネントの作成

  - **目的**: レシートの編集モーダルと削除確認ダイアログを実装する
  - **やること**:
    - `src/features/receipt/components/EditReceiptModal.tsx`:
      - Modal コンポーネントを利用
      - 5フィールドの編集フォーム（日付・金額・支払先・品目・税区分）
      - 現在値を初期値としてセット
      - フロントエンドバリデーション
      - 「保存」ボタン + 「キャンセル」ボタン
      - useUpdateReceipt の update 関数を呼び出す
    - `src/features/receipt/components/DeleteConfirmDialog.tsx`:
      - ConfirmDialog コンポーネントを利用
      - 「このレシートを削除しますか？」メッセージ
      - useDeleteReceipt の confirmDelete / cancelDelete を呼び出す
    - `src/features/receipt/components/index.ts` にエクスポート追加
  - **完了条件**:
    - 編集モーダルが開き、現在値が表示される
    - 保存成功後にモーダルが閉じ、一覧が更新される
    - 削除確認ダイアログで「はい」を選ぶと削除が実行される
    - 削除確認ダイアログで「いいえ」を選ぶとダイアログが閉じる
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.3 PUT /api/receipts/{receiptId}
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §7.2 receipt エラー処理
  - **依存**: Task 1.5（Modal / ConfirmDialog）, Task 4.6（useUpdateReceipt / useDeleteReceipt）
  - **推定時間**: 1時間30分

---

- [ ] **Task 4.13**: ReceiptListPage の組み立て

  - **目的**: 画面C のページコンポーネントを実装し、Feature コンポーネントを組み合わせる
  - **やること**:
    - `src/pages/ReceiptListPage.tsx` を更新:
      - useReceipts で一覧データ取得
      - useReceiptSelection で選択状態管理
      - useExport でエクスポートフロー管理
      - FilterBar + ReceiptTable + Pagination + ExportActionBar の配置
      - EditReceiptModal と DeleteConfirmDialog の条件付き表示
  - **完了条件**:
    - レシート一覧がテーブル形式で表示される
    - フィルタ・ソート・ページングが正しく動作する
    - 編集・削除が正常に動作する
    - CSV エクスポートの一連のフロー（選択 → エクスポート → ダウンロード）が動作する
  - **参照**:
    - `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` の §6 ページコンポーネント
    - `@docs/3.詳細設計/シーケンス図.md` の §4
  - **依存**: Task 4.4〜4.12
  - **推定時間**: 1時間

---

### Phase 5: 結合・仕上げ

**この Phase のゴール**:
3画面を通した一連のフロー（アップロード → OCR → 確認 → 一覧 → エクスポート）が
端から端まで動作し、エラーハンドリング・レスポンシブ対応が完了した状態にする。

**共通参照**:

- `@docs/3.詳細設計/エラーハンドリング設計書.md` - §7 フロントエンド全体
- `@docs/1.要件定義/要件定義書.md` - §5 スコープ外（レスポンシブは最低限対応）

---

- [ ] **Task 5.1**: Axios インターセプターと NotificationContext の統合

  - **目的**: Task 1.3 で保留していた Axios の共通エラーハンドリングと NotificationContext を統合する
  - **やること**:
    - `src/lib/axios.ts` のインターセプターから NotificationContext の showError を呼び出せるようにする
    - 方法: Axios インスタンスにイベントリスナーパターンを導入するか、
      グローバルな通知関数を export して Context 外から呼び出せるようにする
    - 403 → 「アクセス権限がありません」通知
    - 500 → 「サーバーエラーが発生しました。時間をおいて再度お試しください」通知
  - **完了条件**:
    - 任意の API 呼び出しで 403 / 500 が返された時に自動的にトースト通知が表示される
    - Feature 側で個別に 403 / 500 のハンドリングが不要になる
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §7.1 共通エラー処理
  - **依存**: Task 1.3, Task 1.6
  - **推定時間**: 45分

---

- [ ] **Task 5.2**: 画面間フローの結合テスト

  - **目的**: 3画面を通した一連のフローが正しく動作することを確認し、問題があれば修正する
  - **やること**:
    - フロー①のテスト:
      - 画面A でファイル選択 → アップロード → 画面B に遷移
      - 画面B でポーリングによるステータス更新 → 確認・編集 → 確定
    - フロー②のテスト:
      - 画面C で一覧表示 → フィルタ → ソート → ページング
      - チェックボックスで選択 → エクスポート → ダウンロード
    - エラーケースのテスト:
      - 不正なファイルのバリデーションエラー
      - FAILED ジョブの手動リトライ
      - 404 / 409 エラー時の UI フィードバック
      - 401 時のログインリダイレクト
      - 403 時の共通エラー通知（アクセス権限なし）
      - 500 時の共通エラー通知（サーバーエラー）
    - 発見した不具合の修正
  - **完了条件**:
    - フロー①②のハッピーパスが正常動作する
    - 主要なエラーケースで適切なフィードバックが表示される
    - 401 / 403 / 500 の共通エラーハンドリングが仕様どおり動作する
    - コンソールにエラーが出力されない
  - **参照**:
    - `@docs/3.詳細設計/シーケンス図.md` の全フロー
  - **依存**: Phase 1〜4 の全タスク
  - **推定時間**: 2時間

---

- [ ] **Task 5.3**: レスポンシブ対応（最低限）

  - **目的**: タブレット・モバイル端末で最低限の表示崩れがない状態にする
  - **やること**:
    - Header のナビゲーションリンクをモバイルではハンバーガーメニュー化
    - 画面B の2カラムレイアウトをモバイルでは1カラムに切り替え
    - 画面C のテーブルをモバイルでは横スクロール可能に
    - FilterBar のレイアウトをモバイルでは縦積みに切り替え
    - Tailwind CSS のレスポンシブユーティリティ（`sm:`, `md:`, `lg:`）を活用
  - **完了条件**:
    - 375px 幅（モバイル）でレイアウト崩れがない
    - 768px 幅（タブレット）で操作可能
    - 1280px 幅（デスクトップ）でデフォルトレイアウト
  - **参照**:
    - `@docs/1.要件定義/要件定義書.md` の §5 スコープ外（レスポンシブは最低限対応）
  - **依存**: Phase 1〜4 の全タスク
  - **推定時間**: 2時間

---

## ✅ 確定した設計判断

### 1. 画面単位の3モジュール分割

- [x] upload / ocrjob / receipt の3モジュールに分割
  - → **確定**: バックエンドの4モジュール（upload, ocrjob, receipt, export）とは異なるが、
    フロントエンドでは画面単位が自然な分割。export は receipt Feature 内に含める
  - → API サービス関数を `receiptService.ts` + `exportService.ts` に分離して対応

**理由:**

- ExportActionBar が画面C に属するため、独立モジュールにすると選択状態のクロス依存が発生
- 1モジュール = 1画面で直感的

### 2. React Context の利用範囲

- [x] NotificationContext のみ → **確定**
  - → サーバー状態は Context に入れない（カスタムフック内の useState で管理）
  - → batchId は URL パラメータで管理

**理由:**

- Context の再レンダリング範囲を最小化
- URL パラメータによりブックマーク・ブラウザバックに対応

### 3. ポーリング方式の採用

- [x] SSE や WebSocket ではなくフロントエンドポーリングを採用 → **確定**
  - → OCR ステータス: 2秒間隔
  - → エクスポートステータス: 2秒間隔

**理由:**

- 実装がシンプルで、今回の規模では十分
- 他の学習テーマ（非同期処理・ファイルアップロード）に集中

### 4. テストフレームワーク

- [x] Vitest を採用 → **確定**
  - → 要件定義では Jest と記載されていたが、プロジェクトには Vitest がセットアップ済み

**理由:**

- Vite ベースのプロジェクトと親和性が高い
- Jest 互換の API を持ち、移行コストがほぼゼロ

---

## 📅 マイルストーン

- **Phase 1**: 共通基盤（2〜3日）
- **Phase 2**: アップロード機能（1〜2日）
- **Phase 3**: OCR ジョブ処理・確認機能（3〜4日）
- **Phase 4**: レシート一覧・エクスポート機能（3〜4日）
- **Phase 5**: 結合・仕上げ（1〜2日）

**合計推定**: 10〜15日

---

## 📚 参考ドキュメント

- `@docs/1.要件定義/要件定義書.md` - 要件定義書（機能要件・非機能要件）
- `@docs/1.要件定義/要件定義QA.md` - 要件定義の問答記録
- `@docs/2.基本設計/API設計書.md` - API 設計書（エンドポイント・レスポンス形式）
- `@docs/2.基本設計/DBスキーマ設計書.md` - DB スキーマ設計書（テーブル定義・ステータス遷移）
- `@docs/2.基本設計/非同期処理フロー設計書.md` - 非同期処理フロー設計書（ポーリング仕様）
- `@docs/2.基本設計/領収書OCR画面デザイン.pen` - 画面デザイン
- `@docs/3.詳細設計/フロントエンドコンポーネント設計書.md` - コンポーネント設計書
- `@docs/3.詳細設計/シーケンス図.md` - シーケンス図
- `@docs/3.詳細設計/エラーハンドリング設計書.md` - エラーハンドリング設計書

---

**作成日**: 2026-03-01
**最終更新**: 2026-03-01
