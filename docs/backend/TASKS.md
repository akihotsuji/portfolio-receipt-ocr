# 領収書 OCR バックエンド - タスク一覧

## 🎯 概要

領収書 OCR アプリケーションのバックエンド（Spring Boot + Java 21）の実装タスク一覧。
4つの機能モジュール（uploadbatch / ocrjob / receipt / export）をクリーンアーキテクチャで実装し、
MyBatis による SQL ベースのデータアクセス、SQS による非同期処理、Tesseract OCR による画像読み取りを学習テーマとする。

## 🏗️ アーキテクチャ上の重要ポイント

### JPA → MyBatis への移行が必要（重要！）

**現在の pom.xml に `spring-boot-starter-data-jpa` が含まれているが、MyBatis に置き換える**

- 要件定義で ORM として MyBatis を採用する方針が確定済み
- `spring-boot-starter-data-jpa` を削除し、`mybatis-spring-boot-starter` を追加する
- `application.yml` から `spring.jpa.*` セクションを削除し、`mybatis.*` の設定に置き換える

**実装への影響:**

```java
// JPA の JpaRepository は使わない
// MyBatis の @Mapper + XML による SQL 定義を採用
@Mapper
public interface OcrJobMapper {
    @Select("SELECT * FROM receipt_ocr.ocr_jobs WHERE id = #{id}")
    OcrJob findById(UUID id);
}
```

### クリーンアーキテクチャ + 機能モジュール分割（重要！）

**バックエンドの4モジュールはそれぞれ独立した4層構造を持つ**

- `presentation` → `application` → `domain` ← `infrastructure`
- `domain` 層は他レイヤーに依存しない（最内層）
- `infrastructure` は `domain` のインターフェースを実装する（依存性逆転）
- モジュール間の依存は application 層の Service 経由で単方向のみ

```
uploadbatch ──→ ocrjob ──→ receipt ←── export
```

### ワーカーは API と同一プロセスで同居（重要！）

**SQS リスナー（`@SqsListener`）は各機能モジュール内の infrastructure/messaging に配置する**

- OCR ワーカーは `ocrjob/infrastructure/messaging/OcrJobMessageListener.java`
- エクスポートワーカーは `export/infrastructure/messaging/ExportJobMessageListener.java`
- 別プロセスには分離しない（学習目的のため構成をシンプルに保つ）

---

## 📱 機能要件

### 1. アップロードバッチ（Phase 3）

- multipart/form-data でファイル受付（1〜10枚、JPEG/PNG/PDF、10MB以下）
- MinIO にファイル保存 → DB にバッチ・ジョブレコード作成 → SQS にメッセージ投入
- バッチステータスのポーリング用エンドポイント提供

### 2. OCR ジョブ（Phase 4）

- SQS からメッセージ受信 → MinIO から画像取得 → Tesseract OCR 実行 → 結果を DB 保存
- PDF の場合は PDFBox で画像変換後に OCR
- 自動リトライ（SQS 再配信、最大3回）+ 手動リトライ
- 署名付き URL による画像プレビュー提供
- OCR 結果の確認・編集・確定 → レシート作成

### 3. レシート（Phase 5）

- 確定済みレシートの CRUD（一覧・詳細・更新・削除）
- ページング・ソート・フィルタ対応の動的クエリ

### 4. エクスポート（Phase 6）

- 選択レシートの CSV エクスポートジョブ作成 → SQS 投入
- ワーカーが CSV 生成 → MinIO に保存
- 完了後にダウンロードエンドポイントで CSV 返却

---

## 🏗️ 技術スタック

- **言語**: Java 21
- **フレームワーク**: Spring Boot 4.x
- **ORM**: MyBatis（mybatis-spring-boot-starter）
- **データベース**: PostgreSQL 16
- **マイグレーション**: Flyway（未実装。Phase 1 で導入）
- **メッセージキュー**: Amazon SQS（Spring Cloud AWS / ローカルは LocalStack）
- **ストレージ**: MinIO / S3（AWS SDK v2）
- **OCR**: Tesseract OCR（Tess4J）
- **PDF 変換**: Apache PDFBox
- **認証**: JWT（Nimbus JOSE + JWT、実装済み）

---

## 📋 タスクリスト

### Phase 1: プロジェクト基盤整備

**この Phase のゴール**:
JPA から MyBatis への移行を完了し、必要な依存関係を追加して、
アプリケーションが正常に起動する状態にする。
後続の Phase で機能実装に着手できる土台が整った状態にする。

**共通参照**:

- `@docs/3.詳細設計/バックエンドクラス設計書.md` - パッケージ構成・レイヤー規約
- `@docs/2.基本設計/DBスキーマ設計書.md` - テーブル定義

---

- [x] **Task 1.1**: JPA → MyBatis への依存関係移行 ✅ 完了

  - **目的**: 設計方針に従い ORM を JPA から MyBatis に切り替え、
    SQL ベースのデータアクセスを採用するための基盤を整える
  - **やること**:
    - `pom.xml` から `spring-boot-starter-data-jpa` を削除
    - `pom.xml` に以下を追加:
      - `mybatis-spring-boot-starter`（MyBatis 本体）
    - `application.yml` から `spring.jpa.*` セクションを削除
    - `application.yml` に `mybatis.*` の設定を追加:
      - `mybatis.mapper-locations: classpath:mapper/*.xml`
      - `mybatis.configuration.map-underscore-to-camel-case: true`
      - `mybatis.type-aliases-package: com.portfolio.receiptocr`
  - **完了条件**:
    - `mvn compile` がエラーなく通る
    - Spring Boot が正常に起動する（`mvn spring-boot:run`）
    - Flyway マイグレーションが正常に実行される
  - **参照**:
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §7 MyBatis 永続化パターン
    - `backend/pom.xml`（現在の依存関係）
    - `backend/src/main/resources/application.yml`（現在の設定）
  - **推定時間**: 30分

---

- [ ] **Task 1.2**: 追加依存関係の導入

  - **目的**: SQS、MinIO、Tesseract、PDFBox など後続の実装で必要となるライブラリを一括で追加する
  - **やること**:
    - `pom.xml` に以下の依存関係を追加:
      - `io.awspring.cloud:spring-cloud-aws-starter-sqs`（Spring Cloud AWS SQS）
      - `software.amazon.awssdk:s3`（AWS SDK v2 S3 クライアント）
      - `net.sourceforge.tess4j:tess4j`（Tesseract OCR の Java ラッパー）
      - `org.apache.pdfbox:pdfbox`（PDF → 画像変換）
    - Spring Cloud AWS の BOM をインポートしてバージョン管理
    - `application.yml` に SQS / S3（MinIO）の接続設定を追加:
      - SQS: LocalStack エンドポイント（`http://localhost:4566`）
      - S3/MinIO: エンドポイント・アクセスキー・バケット名
  - **完了条件**:
    - `mvn compile` がエラーなく通る
    - Spring Boot が正常に起動する
  - **参照**:
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §7 ローカル開発環境
    - `@docs/1.要件定義/要件定義書.md` の §1.3 技術スタック
  - **推定時間**: 45分

---

- [ ] **Task 1.3**: 共通例外クラスの作成

  - **目的**: 全モジュール共通の例外基盤（DomainException / ErrorCode / ErrorCategory）を作成し、
    後続のモジュール実装で一貫したエラーハンドリングができるようにする
  - **やること**:
    - `shared/domain/exception/ErrorCategory.java` — エラー分類 enum（VALIDATION=400, AUTHENTICATION=401, AUTHORIZATION=403, NOT_FOUND=404, CONFLICT=409, INTERNAL=500）
    - `shared/domain/exception/ErrorCode.java` — エラーコードインターフェース（`code()`, `message()`, `category()`）
    - `shared/domain/exception/CommonErrorCode.java` — 共通エラーコード enum（UNAUTHORIZED, FORBIDDEN, INTERNAL_ERROR）
    - `shared/domain/exception/DomainException.java` — ドメイン例外基底クラス（ErrorCode を保持、RuntimeException 継承）
  - **完了条件**:
    - 4つのクラスが `com.portfolio.receiptocr.shared.domain.exception` パッケージに存在する
    - `DomainException` が `ErrorCode` を受け取り、`getErrorCode()` でエラーコードを返却できる
    - `ErrorCategory` から HTTP ステータスコードが取得できる
    - コンパイルが通る
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §2.2〜§2.4（クラス定義とコード例）
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §4.2 shared
  - **推定時間**: 30分

---

- [ ] **Task 1.4**: GlobalExceptionHandler と ApiErrorResponse の作成

  - **目的**: Controller 層からスローされた例外を一元的にキャッチし、
    API 設計書 §2.4 のフォーマットでエラーレスポンスに変換する
  - **やること**:
    - `infrastructure/web/ApiErrorResponse.java` — record クラス（error: { code, message, details }）
    - `infrastructure/web/GlobalExceptionHandler.java` — `@RestControllerAdvice` で以下をハンドリング:
      1. `DomainException` → ErrorCode に基づいた HTTP ステータスとエラーレスポンス
      2. `MethodArgumentNotValidException` → 400 + フィールドエラー詳細
      3. `MaxUploadSizeExceededException` → 400 FILE_TOO_LARGE
      4. `HttpMessageNotReadableException` → 400 VALIDATION_ERROR
      5. `Exception`（その他） → 500 INTERNAL_ERROR + ログ出力
  - **完了条件**:
    - `DomainException` をスローすると、ErrorCode に応じた HTTP ステータスとエラーレスポンスが返る
    - `@Valid` バリデーション失敗時にフィールドレベルの詳細エラーが返る
    - 予期しない例外は 500 でフォールバックされ、スタックトレースがログに出力される
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §5（GlobalExceptionHandler 実装方針）
    - `@docs/2.基本設計/API設計書.md` の §2.4 共通エラーレスポンス
  - **依存**: Task 1.3（共通例外クラス）
  - **推定時間**: 45分

---

- [ ] **Task 1.5**: PageResponse 共通ラッパーの作成

  - **目的**: ページネーション付きレスポンスの共通フォーマットを定義し、
    レシート一覧 API のレスポンスで再利用する
  - **やること**:
    - `shared/presentation/PageResponse.java` — ジェネリクス record クラス:
      - `content: List<T>` — データ一覧
      - `page: PageInfo` — ページ情報（number, size, totalElements, totalPages）
    - `PageInfo` を内部 record として定義
  - **完了条件**:
    - `PageResponse<ReceiptResponse>` のように任意の型でインスタンス化できる
    - JSON シリアライズ結果が API 設計書 §5.3 のレスポンス形式と一致する
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.3 GET /api/receipts レスポンス
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §4.2 shared
  - **推定時間**: 15分

---

- [ ] **Task 1.6**: Flyway 初期マイグレーションの実装

  - **目的**: DB スキーマ設計書に定義されたテーブル・制約・インデックス・トリガーを
    Flyway で再現し、ローカル起動時に自動適用される状態にする
  - **やること**:
    - `src/main/resources/db/migration/V1__init_receipt_ocr_schema.sql` を作成:
      - スキーマ `receipt_ocr` 作成
      - テーブル作成:
        - `upload_batches`
        - `ocr_jobs`
        - `receipts`
        - `export_jobs`
        - `export_job_receipts`
      - 制約作成:
        - PK / FK / UNIQUE / CHECK（status, retry_count, amount など）
      - インデックス作成（DB スキーマ設計書の定義に準拠）
      - `updated_at` 自動更新トリガー関数 + トリガー作成（`ocr_jobs`, `receipts`）
    - `application.yml` に Flyway 設定を追加/確認:
      - `spring.flyway.enabled: true`
      - `spring.flyway.schemas: receipt_ocr`
      - `spring.flyway.locations: classpath:db/migration`
  - **完了条件**:
    - 空の DB に対してアプリ起動時に V1 マイグレーションが適用される
    - 5テーブル・主要制約・主要インデックス・`updated_at` トリガーが作成される
    - `flyway_schema_history` に V1 の適用履歴が記録される
  - **参照**:
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §3（テーブル詳細）, §5.8（updated_at 自動更新）
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §5.9（ON DELETE 方針）
  - **依存**: Task 1.2
  - **推定時間**: 1時間30分

---

### Phase 2: 横断的インフラ

**この Phase のゴール**:
MinIO/S3 へのファイル操作（保存・取得・署名付き URL 生成）と
SQS へのメッセージ送信が動作する状態にする。
これにより Phase 3 以降のモジュール実装で外部サービス連携がすぐに利用可能になる。

**共通参照**:

- `@docs/3.詳細設計/バックエンドクラス設計書.md` の §4.1 横断的インフラ
- `@docs/2.基本設計/非同期処理フロー設計書.md` - SQS キュー定義・メッセージ形式

---

- [ ] **Task 2.1**: StorageClient インターフェースと MinioStorageClient の作成

  - **目的**: オブジェクトストレージ操作を抽象化し、MinIO / S3 の切り替えを容易にする
  - **やること**:
    - `infrastructure/storage/StorageClient.java` — インターフェース:
      - `putObject(String key, InputStream data, long size, String contentType)` — ファイル保存
      - `getObject(String key): InputStream` — ファイル取得
      - `generatePresignedUrl(String key, Duration expiration): String` — 署名付き URL 生成
      - `deleteObject(String key)` — ファイル削除
    - `infrastructure/storage/MinioStorageClient.java` — AWS SDK v2 の S3Client を利用した実装:
      - `@Configuration` で S3Client Bean を生成（MinIO エンドポイント対応）
      - path-style access を有効化（MinIO 互換）
      - バケット名は `application.yml` から注入
    - `application.yml` にストレージ関連の設定を追加:
      - バケット名（`receipt-uploads`）
      - 署名付き URL の有効期限（15分）
  - **完了条件**:
    - MinIO にファイルのアップロード・ダウンロードができる
    - 署名付き URL が生成でき、期限付きでアクセスできる
    - ファイルの削除ができる
  - **参照**:
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §4.1（StorageClient / MinioStorageClient）
    - `@docs/2.基本設計/API設計書.md` の §5.2 GET /api/ocr-jobs/{jobId}/image-url
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §5.2（storage_key / converted_image_key の用途）
  - **推定時間**: 1時間

---

- [ ] **Task 2.2**: SqsMessageSender の作成

  - **目的**: SQS へのメッセージ送信を共通化し、OCR ジョブとエクスポートジョブの両方で利用する
  - **やること**:
    - `infrastructure/messaging/SqsMessageSender.java`:
      - Spring Cloud AWS の `SqsTemplate` を利用
      - `send(String queueName, Object message)` — メッセージを JSON シリアライズして送信
    - `application.yml` にキュー名の設定を追加:
      - `app.sqs.ocr-queue: receipt-ocr-queue`
      - `app.sqs.export-queue: receipt-export-queue`
    - LocalStack 用の SQS エンドポイント設定
  - **完了条件**:
    - OCR ジョブメッセージを `receipt-ocr-queue` に送信できる
    - エクスポートジョブメッセージを `receipt-export-queue` に送信できる
    - LocalStack の SQS にメッセージが投入されることを確認できる
  - **参照**:
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §3（SQS キュー定義・メッセージ形式）
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §4.1（SqsMessageSender）
  - **推定時間**: 45分

---

- [ ] **Task 2.3**: SecurityConfig の更新（API パスの認可設定）

  - **目的**: 新しいエンドポイント（upload-batches, ocr-jobs, receipts, exports）への
    認可ルールを設定する
  - **やること**:
    - `infrastructure/security/SecurityConfig.java` を更新:
      - `/api/health`, `/api/info` → 認証不要（actuator）
      - `/api/upload-batches/**` → 認証必須
      - `/api/ocr-jobs/**` → 認証必須
      - `/api/receipts/**` → 認証必須
      - `/api/exports/**` → 認証必須
      - CORS 設定（フロントエンドからのアクセス許可）
      - multipart/form-data の受付許可
      - ファイルアップロードサイズ上限の設定（`spring.servlet.multipart.max-file-size: 10MB`, `max-request-size: 100MB`）
  - **完了条件**:
    - JWT なしのリクエストが 401 で拒否される
    - 有効な JWT 付きのリクエストが認可される
    - actuator エンドポイントが認証なしでアクセスできる
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §2.2 認証
    - `backend/src/main/java/com/portfolio/receiptocr/infrastructure/security/SecurityConfig.java`（既存コード）
  - **推定時間**: 30分

---

- [ ] **Task 2.4**: SQS ワーカー並列数（OCR=3 / Export=3）の設定

  - **目的**: 要件定義の並列処理要件（OCR N=3、Export M=3）を
    `@SqsListener` 実行基盤で確実に満たす
  - **やること**:
    - `application.yml` と Messaging 設定クラスで SQS リスナーの並列実行数を設定:
      - OCR ワーカー: 3 並列
      - エクスポートワーカー: 3 並列
    - 必要に応じて Listener ごとに `factory` を分けて並列数を個別指定
    - ローカル検証:
      - OCR キューに 10 件投入し、同時実行が最大 3 であることを確認
      - Export キューに 10 件投入し、同時実行が最大 3 であることを確認
  - **完了条件**:
    - OCR / Export の両ワーカーで並列数 3 が実測で確認できる
    - 並列数を設定値で変更できる（ハードコードしない）
  - **参照**:
    - `@docs/1.要件定義/要件定義書.md` の §3.2（OCR N=3 / Export M=3）
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §4.2（スレッドプール設定）
  - **依存**: Task 1.2, Task 2.2
  - **推定時間**: 45分

---

### Phase 3: アップロードバッチ機能

**この Phase のゴール**:
フロントエンドから multipart/form-data でファイルをアップロードし、
MinIO に保存 → DB にバッチ・ジョブレコード作成 → SQS にメッセージ投入が完了する状態にする。
また、バッチのステータスをポーリング（2秒間隔）で取得できるエンドポイントを提供する。

**共通参照**:

- `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.1 uploadbatch
- `@docs/2.基本設計/API設計書.md` の §5.1 アップロードバッチ
- `@docs/3.詳細設計/シーケンス図.md` の §2.1 アップロードシーケンス

---

- [ ] **Task 3.1**: UploadBatch ドメインモデルと Repository の作成

  - **目的**: アップロードバッチのドメインモデルと永続化インターフェースを定義する
  - **やること**:
    - `uploadbatch/domain/model/UploadBatch.java`:
      - フィールド: id (UUID), userId (String), totalFiles (int), createdAt (Instant)
      - ファクトリメソッド `create(String userId, int totalFiles)` で生成
    - `uploadbatch/domain/repository/UploadBatchRepository.java`:
      - `save(UploadBatch batch)` — バッチを保存し ID を返却
      - `findById(UUID id): Optional<UploadBatch>` — ID で取得
      - `findByIdAndUserId(UUID id, String userId): Optional<UploadBatch>` — ID + ユーザーで取得
  - **完了条件**:
    - ドメインモデルが不変条件を保護している（totalFiles >= 1）
    - Repository インターフェースが MyBatis に依存していない
  - **参照**:
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §3.1 upload_batches
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.1
  - **推定時間**: 20分

---

- [ ] **Task 3.2**: UploadBatch MyBatis Mapper と RepositoryImpl の作成

  - **目的**: MyBatis による永続化実装を作成し、DB との接続を確立する
  - **やること**:
    - `uploadbatch/infrastructure/mybatis/UploadBatchMapper.java` — `@Mapper` インターフェース:
      - `insert(UploadBatch batch)` — INSERT
      - `findById(UUID id): UploadBatch` — SELECT
      - `findByIdAndUserId(UUID id, String userId): UploadBatch` — SELECT
    - `src/main/resources/mapper/UploadBatchMapper.xml`:
      - `resultMap` でカラム → ドメインモデルのマッピング定義
      - INSERT / SELECT の SQL 定義
    - `uploadbatch/infrastructure/mybatis/UploadBatchRepositoryImpl.java`:
      - `UploadBatchRepository` を実装し、`UploadBatchMapper` に委譲
  - **完了条件**:
    - `UploadBatchRepositoryImpl` 経由で DB への INSERT / SELECT が動作する
    - `resultMap` により DB 行がドメインモデルに正しくマッピングされる
  - **参照**:
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §7 MyBatis 永続化パターン
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §3.1（カラム定義）
  - **依存**: Task 3.1
  - **推定時間**: 30分

---

- [ ] **Task 3.3**: UploadBatch エラーコードの作成

  - **目的**: アップロードバッチ固有のエラーコード（ファイルバリデーション等）を定義する
  - **やること**:
    - `uploadbatch/domain/exception/UploadBatchErrorCode.java` — enum:
      - `FILE_TOO_LARGE`（400, VALIDATION）
      - `FILE_TYPE_NOT_ALLOWED`（400, VALIDATION）
      - `TOO_MANY_FILES`（400, VALIDATION）
      - `BATCH_NOT_FOUND`（404, NOT_FOUND）
    - `uploadbatch/domain/exception/UploadBatchException.java` — `DomainException` のサブクラス
  - **完了条件**:
    - 4つのエラーコードが定義され、ErrorCode インターフェースを実装している
    - `UploadBatchException` が `UploadBatchErrorCode` を受け取れる
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §3.2（uploadbatch エラーコード）
  - **依存**: Task 1.3（共通例外クラス）
  - **推定時間**: 15分

---

- [ ] **Task 3.4**: UploadBatchService の作成

  - **目的**: ファイルアップロードの一連のビジネスロジック
    （バリデーション → MinIO 保存 → DB 登録 → SQS 投入）を統合する
  - **やること**:
    - `uploadbatch/application/UploadBatchService.java`:
      - `createBatch(List<MultipartFile> files, String userId)`:
        1. ファイルバリデーション（枚数: 1〜10、サイズ: 10MB以下、形式: JPEG/PNG/PDF）
        2. 各ファイルを MinIO に保存（storageKey: `{batchId}/{fileName}`）
        3. `upload_batches` レコードを INSERT
        4. ファイルごとに `OcrJobService.createJob()` を呼び出し
        5. `CreateBatchResponse` を返却
      - `getBatchStatus(UUID batchId, String userId)`:
        1. バッチの存在確認 + ユーザー権限チェック
        2. バッチに紐づく全 OCR ジョブの最新ステータスを取得
        3. summary（ステータス別件数）を集計
        4. `BatchStatusResponse` を返却
    - `@Transactional` でトランザクション管理
  - **完了条件**:
    - 不正なファイル（形式・サイズ・枚数）が `UploadBatchException` で拒否される
    - 正常系で MinIO 保存 → DB INSERT → SQS 送信が一連で完了する
    - 他ユーザーのバッチへのアクセスが `FORBIDDEN` で拒否される
  - **参照**:
    - `@docs/3.詳細設計/シーケンス図.md` の §2.1（アップロードシーケンス）
    - `@docs/2.基本設計/API設計書.md` の §5.1 処理フロー
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §6.2 Application 層方針
  - **依存**: Task 3.1, Task 3.2, Task 3.3, Task 2.1（StorageClient）, Task 2.2（SqsMessageSender）
  - **推定時間**: 1時間30分

---

- [ ] **Task 3.5**: UploadBatchController と DTO の作成

  - **目的**: アップロードバッチの2つの API エンドポイントを実装する
  - **やること**:
    - `uploadbatch/presentation/dto/response/CreateBatchResponse.java` — record:
      - batchId, jobs (List<JobSummary>), totalFiles, createdAt
      - 内部 record `JobSummary`（jobId, fileName, fileSize, mimeType, status, createdAt）
    - `uploadbatch/presentation/dto/response/BatchStatusResponse.java` — record:
      - batchId, jobs (List<JobStatus>), summary, createdAt
      - 内部 record `JobStatus`（jobId, fileName, status, retryCount, createdAt, completedAt）
      - 内部 record `Summary`（total, queued, processing, completed, confirmed, failed）
    - `uploadbatch/presentation/UploadBatchController.java`:
      - `POST /api/upload-batches` — multipart/form-data を受付、`UploadBatchService.createBatch()` を呼び出し、201 Created
      - `GET /api/upload-batches/{batchId}` — `UploadBatchService.getBatchStatus()` を呼び出し、200 OK
      - JWT から userId を取得（`@AuthenticationPrincipal` または SecurityContext）
  - **完了条件**:
    - ファイルアップロード API が正常に動作し、201 Created でバッチ情報が返る
    - バッチステータス取得 API が summary 含むレスポンスを返す
    - 不正なリクエスト（ファイルなし、サイズ超過等）で適切なエラーレスポンスが返る
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.1（レスポンス JSON 例）
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.1（クラス一覧）
  - **依存**: Task 3.4
  - **推定時間**: 1時間

---

### Phase 4: OCR ジョブ機能

**この Phase のゴール**:
SQS からメッセージを受信して Tesseract OCR で画像を読み取り、結果を DB に保存する
非同期ワーカーが動作する状態にする。
また、OCR 結果の確認・編集・確定、失敗ジョブの手動リトライ、
画像プレビュー用の署名付き URL 生成が完了する状態にする。

**共通参照**:

- `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.2 ocrjob
- `@docs/2.基本設計/API設計書.md` の §5.2 OCR ジョブ
- `@docs/2.基本設計/非同期処理フロー設計書.md` - 非同期処理フロー
- `@docs/3.詳細設計/シーケンス図.md` の §2, §3

**重要な設計決定**:

- リトライは SQS の再配信メカニズムに委譲する（アプリ側でリトライ回数を管理しない）
- 冪等性はステータスチェック（`status = QUEUED` でなければスキップ）で保証する
- OCR タイムアウトは 60 秒、Visibility Timeout は 120 秒

---

- [ ] **Task 4.1**: OcrJob ドメインモデル・OcrResult 値オブジェクト・Repository の作成

  - **目的**: OCR ジョブのドメインモデルにステータス遷移ルールを組み込み、
    不正な状態遷移をドメイン層で防止する
  - **やること**:
    - `ocrjob/domain/model/OcrJob.java`:
      - フィールド: id, batchId, userId, fileName, fileSize, mimeType, storageKey, convertedImageKey, status, retryCount, errorMessage, ocrResult, createdAt, updatedAt, completedAt
      - ステータス遷移メソッド:
        - `markAsProcessing()` — QUEUED → PROCESSING（QUEUED でなければ例外）
        - `complete(OcrResult result)` — PROCESSING → COMPLETED
        - `fail(String errorMessage)` — PROCESSING → FAILED + retryCount++
        - `confirm()` — COMPLETED → CONFIRMED（COMPLETED でなければ例外）
        - `resetForRetry()` — FAILED → QUEUED + retryCount を 0 にリセット
    - `ocrjob/domain/model/OcrResult.java` — record（値オブジェクト）:
      - date (LocalDate), amount (Integer), storeName (String), description (String), taxCategory (String), rawText (String)
    - `ocrjob/domain/repository/OcrJobRepository.java`:
      - `save(OcrJob job)`, `findById(UUID id)`, `findByIdAndUserId(UUID id, String userId)`
      - `findByBatchId(UUID batchId): List<OcrJob>` — バッチ内の全ジョブ取得
      - `updateStatus(OcrJob job)` — ステータスと関連フィールドの更新
      - `updateOcrResult(OcrJob job)` — OCR 結果の保存
  - **完了条件**:
    - `markAsProcessing()` を PROCESSING 状態のジョブに呼ぶと `DomainException` がスローされる
    - `confirm()` を QUEUED 状態のジョブに呼ぶと `DomainException` がスローされる
    - ステータス遷移がドメインモデル内で完結している
  - **参照**:
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §3.2 ocr_jobs, §4.1 ステータス遷移
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §6.3 Domain 層（OcrJob コード例）
  - **推定時間**: 45分

---

- [ ] **Task 4.2**: OcrJob MyBatis Mapper と RepositoryImpl の作成

  - **目的**: OCR ジョブの MyBatis 永続化層を実装する。
    `OcrResult` の association マッピングを含む
  - **やること**:
    - `ocrjob/infrastructure/mybatis/OcrJobMapper.java` — `@Mapper` インターフェース:
      - `insert(OcrJob job)`, `findById(UUID id)`, `findByIdAndUserId(UUID id, String userId)`
      - `findByBatchId(UUID batchId): List<OcrJob>`
      - `updateStatus(OcrJob job)` — status, retryCount, errorMessage, completedAt を更新
      - `markAsProcessingIfQueued(UUID id, Instant expectedUpdatedAt): int` — `status=QUEUED` + `updated_at` 条件で更新（楽観ロック）
      - `updateOcrResult(OcrJob job)` — ocr_* カラムと status を更新
    - `src/main/resources/mapper/OcrJobMapper.xml`:
      - `resultMap` に `OcrResult` の `<association>` マッピングを含む
        （ocr_date → ocrResult.date, ocr_amount → ocrResult.amount 等）
      - 各 SQL の定義
      - `markAsProcessingIfQueued` は `WHERE id = ? AND status = 'QUEUED' AND updated_at = ?` で実装
    - `ocrjob/infrastructure/mybatis/OcrJobRepositoryImpl.java`:
      - `OcrJobRepository` を実装し、Mapper に委譲
  - **完了条件**:
    - `OcrJob` の INSERT / SELECT / UPDATE が正しく動作する
    - `OcrResult` が `<association>` で正しくマッピングされる（OCR 未完了時は null）
    - `findByBatchId` でバッチ内の全ジョブが取得できる
    - 楽観ロック競合時に `markAsProcessingIfQueued` の更新件数が 0 になり、二重開始を防止できる
  - **参照**:
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §7.3（resultMap + association の XML 例）
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §6 API レスポンスとのマッピング
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §5.4（DB の楽観ロック）
  - **依存**: Task 4.1
  - **推定時間**: 1時間

---

- [ ] **Task 4.3**: OcrJob エラーコードの作成

  - **目的**: OCR ジョブ固有のエラーコード（ステータス不整合等）を定義する
  - **やること**:
    - `ocrjob/domain/exception/OcrJobErrorCode.java` — enum:
      - `JOB_NOT_FOUND`（404, NOT_FOUND）
      - `JOB_NOT_CONFIRMABLE`（409, CONFLICT）— 確定不可能なステータス
      - `JOB_NOT_RETRYABLE`（409, CONFLICT）— リトライ不可能なステータス
      - `OCR_PROCESSING_FAILED`（500, INTERNAL）— OCR 処理中エラー
      - `OCR_TIMEOUT`（500, INTERNAL）— 60秒タイムアウト
    - `ocrjob/domain/exception/OcrJobException.java` — `DomainException` サブクラス
  - **完了条件**:
    - 5つのエラーコードが定義されている
    - API 設計書 §2.5 のエラーコードと対応が取れている
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §3.3（ocrjob エラーコード）
  - **依存**: Task 1.3
  - **推定時間**: 15分

---

- [ ] **Task 4.4**: OcrEngine インターフェースと TesseractOcrEngine の作成

  - **目的**: OCR 処理をインターフェースで抽象化し、テスト時にモック差し替えを可能にする
  - **やること**:
    - `ocrjob/domain/service/OcrEngine.java` — インターフェース（ドメイン層に配置）:
      - `recognize(byte[] imageData): OcrResult` — 画像バイト列から OCR 結果を返す
    - `ocrjob/infrastructure/ocr/TesseractOcrEngine.java` — Tess4J 実装:
      - Tesseract インスタンスの初期化（言語: `jpn`、tessdata パス設定）
      - `recognize()` で画像を OCR し、生テキストを取得
      - 生テキストからの項目抽出（日付・金額・店名・品目・税区分のパース）
      - パース処理の実装方針:
        - 正規表現で日付パターン（`\d{4}年\d{1,2}月\d{1,2}日` 等）を検索
        - 正規表現で金額パターン（`¥[\d,]+` / `\d+円` 等）を検索
        - 最初の行または店名パターンから支払先を推定
        - 読み取れなかった項目は null を返す
  - **完了条件**:
    - `TesseractOcrEngine` が画像バイト列を受け取り、`OcrResult` を返す
    - 日本語テキストが読み取れる（`jpn.traineddata` が利用可能）
    - 各項目のパースが動作する（完璧な精度は不要）
  - **参照**:
    - `@docs/1.要件定義/要件定義書.md` の §2.1 OCR 読み取り項目, §4.1 Tesseract OCR
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.2（OcrEngine / TesseractOcrEngine）
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §8.3 OcrEngine インターフェースの導入
  - **推定時間**: 2時間

---

- [ ] **Task 4.5**: PdfConverter の作成

  - **目的**: PDF ファイルを画像に変換し、Tesseract OCR で処理可能にする
  - **やること**:
    - `ocrjob/infrastructure/ocr/PdfConverter.java`:
      - `convertToImage(byte[] pdfData): byte[]` — PDF の1ページ目を JPEG 画像に変換
      - Apache PDFBox の `PDDocument` + `PDFRenderer` を使用
      - DPI 設定（300 DPI を推奨、OCR 精度とのバランス）
      - 複数ページの場合は1ページ目のみ対象（要件定義 §5 スコープ外）
  - **完了条件**:
    - PDF バイト列を入力すると JPEG 画像バイト列が返る
    - 変換後の画像で OCR が実行可能な品質である
    - 複数ページ PDF でも1ページ目のみが変換される
  - **参照**:
    - `@docs/1.要件定義/要件定義書.md` の §4.2 PDF 変換
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §5.2（converted_image_key の設計意図）
  - **推定時間**: 45分

---

- [ ] **Task 4.6**: OcrJobService の作成

  - **目的**: OCR ジョブの作成・OCR 実行・確定・リトライのビジネスロジックを統合する
  - **やること**:
    - `ocrjob/application/OcrJobService.java`:
      - `createJob(UUID batchId, String userId, FileInfo fileInfo)`:
        - OCR ジョブレコードを DB に INSERT（status=QUEUED）
        - SQS にメッセージ送信（jobId, batchId, storageKey, mimeType）
      - `processOcrJob(OcrJobMessage message)`:
        - 冪等性チェック（status が QUEUED でなければスキップ、WARN ログ）
        - `markAsProcessingIfQueued`（楽観ロック）で PROCESSING に更新（更新件数 0 は競合としてスキップ）
        - MinIO から画像取得
        - PDF の場合は `PdfConverter` で変換 → 変換後画像を MinIO に保存 → convertedImageKey を記録
        - `OcrEngine.recognize()` を `CompletableFuture.get(60, SECONDS)` でタイムアウト制御
        - 成功: OCR 結果を DB に保存、ステータスを COMPLETED に更新
        - 失敗: ステータスを FAILED に更新、error_message を記録、例外を再スロー
      - `getJob(UUID jobId, String userId)` — ジョブ詳細取得 + 権限チェック
      - `getImageUrl(UUID jobId, String userId)` — 署名付き URL 生成（PDF は convertedImageKey を使用）
      - `confirmJob(UUID jobId, ConfirmRequest request, String userId)`:
        - ステータスチェック（COMPLETED のみ確定可能）
        - ジョブを CONFIRMED に更新
        - `ReceiptService.createReceipt()` を呼び出しレシート作成
      - `retryJob(UUID jobId, String userId)`:
        - ステータスチェック（FAILED のみリトライ可能）
        - retryCount を 0 にリセット、ステータスを QUEUED に変更
        - SQS にメッセージを再投入
    - `@Transactional` でトランザクション管理
  - **完了条件**:
    - OCR 処理が非同期で実行され、成功時に COMPLETED に遷移する
    - 60秒タイムアウト時に FAILED に遷移し、例外が再スローされる
    - 冪等性チェック + 楽観ロックにより二重処理が防止される
    - 確定時にレシートが作成され、ジョブが CONFIRMED に遷移する
    - リトライ時にジョブが QUEUED に戻り、SQS にメッセージが投入される
  - **参照**:
    - `@docs/3.詳細設計/シーケンス図.md` の §2.1（OCR 処理）, §3（確定）, §2.3（手動リトライ）
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §5（リトライ・タイムアウト設計）
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §6.2, §6.4（レイヤー別方針）
  - **依存**: Task 4.1, Task 4.2, Task 4.3, Task 4.4, Task 4.5, Task 2.1, Task 2.2
  - **推定時間**: 2時間

---

- [ ] **Task 4.7**: OcrJobMessageListener の作成

  - **目的**: SQS からの OCR ジョブメッセージを受信し、Service に処理を委譲する
  - **やること**:
    - `ocrjob/infrastructure/messaging/OcrJobMessage.java` — record:
      - jobId (UUID), batchId (UUID), storageKey (String), mimeType (String)
    - `ocrjob/infrastructure/messaging/OcrJobMessageListener.java`:
      - `@SqsListener("receipt-ocr-queue")` でメッセージ受信
      - `OcrJobService.processOcrJob(message)` に委譲
      - 例外が発生した場合はログ出力後に再スロー（SQS の自動再配信に委ねる）
  - **完了条件**:
    - SQS にメッセージが投入されると `@SqsListener` が受信する
    - 処理成功時にメッセージが自動削除される
    - 処理失敗時にメッセージが残り、Visibility Timeout 後に再配信される
  - **参照**:
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §4.3 ポーリング方式
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §6.4 Infrastructure 層
  - **依存**: Task 4.6
  - **推定時間**: 30分

---

- [ ] **Task 4.8**: OcrJobController と DTO の作成

  - **目的**: OCR ジョブの4つの API エンドポイントを実装する
  - **やること**:
    - `ocrjob/presentation/dto/request/ConfirmRequest.java` — record + Bean Validation:
      - date (LocalDate, @NotNull), amount (Integer, @NotNull, @Min(1))
      - storeName (String, @NotBlank, @Size(max=200))
      - description (String, @Size(max=500))
      - taxCategory (String)
    - `ocrjob/presentation/dto/response/OcrJobResponse.java` — record:
      - jobId, batchId, fileName, fileSize, mimeType, status, retryCount, ocrResult, errorMessage, createdAt, completedAt
    - `ocrjob/presentation/dto/response/ImageUrlResponse.java` — record:
      - imageUrl, expiresAt
    - `ocrjob/presentation/OcrJobController.java`:
      - `GET /api/ocr-jobs/{jobId}` — 200 OK
      - `GET /api/ocr-jobs/{jobId}/image-url` — 200 OK
      - `POST /api/ocr-jobs/{jobId}/confirm` — @Valid + 201 Created
      - `POST /api/ocr-jobs/{jobId}/retry` — 200 OK
  - **完了条件**:
    - 4つのエンドポイントが正常に動作する
    - `@Valid` バリデーションが ConfirmRequest に適用される
    - レスポンス JSON が API 設計書の形式と一致する
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.2（リクエスト/レスポンス JSON 例）
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.2（クラス一覧）
  - **依存**: Task 4.6
  - **推定時間**: 1時間

---

### Phase 5: レシート機能

**この Phase のゴール**:
確定済みレシートの CRUD（一覧・詳細・更新・削除）と、
ページング・ソート・動的フィルタによる検索が完了する状態にする。
MyBatis の動的 SQL（`<if>`, `<where>`）を活用する。

**共通参照**:

- `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.3 receipt
- `@docs/2.基本設計/API設計書.md` の §5.3 レシート
- `@docs/3.詳細設計/シーケンス図.md` の §4.2

---

- [ ] **Task 5.1**: Receipt ドメインモデルと Repository の作成

  - **目的**: レシートのドメインモデルと、検索条件付き取得を含む永続化インターフェースを定義する
  - **やること**:
    - `receipt/domain/model/Receipt.java`:
      - フィールド: id (UUID), jobId (UUID), userId (String), receiptDate (LocalDate), amount (int), storeName (String), description (String), taxCategory (String), confirmedAt (Instant), updatedAt (Instant)
      - ファクトリメソッド `create(UUID jobId, String userId, ...)` で生成
      - `update(...)` メソッドで更新可能フィールドを変更
    - `receipt/domain/repository/ReceiptRepository.java`:
      - `save(Receipt receipt)` — INSERT
      - `findById(UUID id): Optional<Receipt>`
      - `findByIdAndUserId(UUID id, String userId): Optional<Receipt>`
      - `search(ReceiptSearchCriteria criteria, String userId): List<Receipt>` — 検索
      - `countByUserId(ReceiptSearchCriteria criteria, String userId): long` — 件数取得
      - `update(Receipt receipt)` — UPDATE
      - `deleteByIdAndUserId(UUID id, String userId)` — DELETE
      - `findByIdsAndUserId(List<UUID> ids, String userId): List<Receipt>` — 複数 ID 取得
    - `receipt/domain/model/ReceiptSearchCriteria.java` — record:
      - dateFrom, dateTo, storeName, amountMin, amountMax, sort, order, page, size
  - **完了条件**:
    - Repository インターフェースが検索条件・ページング・ソートに対応している
    - ドメインモデルの `update()` で更新可能フィールドのみ変更される
  - **参照**:
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §3.3 receipts
    - `@docs/2.基本設計/API設計書.md` の §5.3 GET /api/receipts クエリパラメータ
  - **推定時間**: 30分

---

- [ ] **Task 5.2**: Receipt MyBatis Mapper と RepositoryImpl の作成

  - **目的**: 動的 SQL によるフィルタ・ソート・ページングを MyBatis XML で実装する
  - **やること**:
    - `receipt/infrastructure/mybatis/ReceiptMapper.java` — `@Mapper` インターフェース
    - `src/main/resources/mapper/ReceiptMapper.xml`:
      - `resultMap` でカラム → ドメインモデルのマッピング
      - INSERT / SELECT / UPDATE / DELETE の SQL 定義
      - `search` クエリ — 動的 SQL:
        - `<where>` + `<if>` でフィルタ条件を動的に組み立て:
          - `user_id = #{userId}`（常に適用）
          - `<if test="dateFrom != null">AND receipt_date >= #{dateFrom}</if>`
          - `<if test="dateTo != null">AND receipt_date <= #{dateTo}</if>`
          - `<if test="storeName != null">AND store_name LIKE CONCAT('%', #{storeName}, '%')</if>`
          - `<if test="amountMin != null">AND amount >= #{amountMin}</if>`
          - `<if test="amountMax != null">AND amount <= #{amountMax}</if>`
        - `ORDER BY` でソート（date, amount, storeName, confirmedAt）
        - `LIMIT #{size} OFFSET #{page * size}` でページング
      - `count` クエリ — search と同じ WHERE 条件で件数を返す
      - `findByIds` クエリ — `<foreach>` で IN 句を生成
    - `receipt/infrastructure/mybatis/ReceiptRepositoryImpl.java`:
      - `ReceiptRepository` を実装し、Mapper に委譲
  - **完了条件**:
    - 動的フィルタ条件が正しく SQL に反映される
    - ソート項目の切り替えが動作する
    - ページングで正しい範囲のデータが返る
    - `count` クエリがフィルタ条件を反映した件数を返す
  - **参照**:
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §7（MyBatis 永続化パターン、動的 SQL）
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §3.3（インデックス定義）
  - **依存**: Task 5.1
  - **推定時間**: 1時間

---

- [ ] **Task 5.3**: Receipt エラーコードの作成

  - **目的**: レシート固有のエラーコードを定義する
  - **やること**:
    - `receipt/domain/exception/ReceiptErrorCode.java` — enum:
      - `RECEIPT_NOT_FOUND`（404, NOT_FOUND）
      - `VALIDATION_ERROR`（400, VALIDATION）
    - `receipt/domain/exception/ReceiptException.java` — `DomainException` サブクラス
  - **完了条件**:
    - エラーコードが ErrorCode インターフェースを実装している
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §3.4（receipt エラーコード）
  - **依存**: Task 1.3
  - **推定時間**: 10分

---

- [ ] **Task 5.4**: ReceiptService の作成

  - **目的**: レシートの CRUD と検索のビジネスロジックを実装する
  - **やること**:
    - `receipt/application/ReceiptService.java`:
      - `createReceipt(UUID jobId, ConfirmData data, String userId)`:
        - レシートレコードを DB に INSERT
        - 作成した Receipt を返却
      - `searchReceipts(ReceiptSearchCriteria criteria, String userId)`:
        - 動的フィルタ + ソート + ページングでレシート一覧を取得
        - 件数も取得して `PageResponse` として返却
      - `getReceipt(UUID receiptId, String userId)`:
        - ID + ユーザーで取得、存在しなければ `NOT_FOUND`
      - `updateReceipt(UUID receiptId, UpdateData data, String userId)`:
        - 存在確認 + 権限チェック → 更新 → 更新後のレシートを返却
      - `deleteReceipt(UUID receiptId, String userId)`:
        - 存在確認 + 権限チェック → 削除
      - `findByIds(List<UUID> ids, String userId): List<Receipt>`:
        - エクスポート用に複数 ID でレシートを取得
    - `@Transactional` でトランザクション管理
  - **完了条件**:
    - CRUD の全操作が正しく動作する
    - 他ユーザーのレシートへのアクセスが `FORBIDDEN` で拒否される
    - 存在しないレシートへのアクセスが `NOT_FOUND` で拒否される
    - 検索結果にページ情報（totalElements, totalPages）が含まれる
  - **参照**:
    - `@docs/3.詳細設計/シーケンス図.md` の §4.2（編集・削除シーケンス）
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §6.2 Application 層方針
  - **依存**: Task 5.1, Task 5.2, Task 5.3
  - **推定時間**: 1時間

---

- [ ] **Task 5.5**: ReceiptController と DTO の作成

  - **目的**: レシートの4つの API エンドポイントを実装する
  - **やること**:
    - `receipt/presentation/dto/request/ReceiptSearchRequest.java` — record:
      - page, size, sort, order, dateFrom, dateTo, storeName, amountMin, amountMax
      - デフォルト値の設定（page=0, size=20, sort="date", order="desc"）
    - `receipt/presentation/dto/request/UpdateReceiptRequest.java` — record + Bean Validation:
      - date (@NotNull), amount (@NotNull, @Min(1)), storeName (@NotBlank, @Size(max=200))
      - description (@Size(max=500)), taxCategory
    - `receipt/presentation/dto/response/ReceiptResponse.java` — record:
      - receiptId, date, amount, storeName, description, taxCategory, confirmedAt, updatedAt
    - `receipt/presentation/ReceiptController.java`:
      - `GET /api/receipts` — クエリパラメータでフィルタ・ソート・ページング、200 OK
      - `GET /api/receipts/{receiptId}` — 200 OK
      - `PUT /api/receipts/{receiptId}` — @Valid + 200 OK
      - `DELETE /api/receipts/{receiptId}` — 204 No Content
  - **完了条件**:
    - 一覧取得でフィルタ・ソート・ページングが正しく動作する
    - 更新時に `@Valid` バリデーションが適用される
    - 削除時に 204 No Content が返る
    - レスポンス JSON が API 設計書の形式と一致する
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.3（リクエスト/レスポンス JSON 例）
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.3
  - **依存**: Task 5.4, Task 1.5（PageResponse）
  - **推定時間**: 1時間

---

### Phase 6: エクスポート機能

**この Phase のゴール**:
選択したレシートの CSV エクスポートジョブを作成し、
SQS ワーカーが CSV を生成して MinIO に保存、
完了後にダウンロードエンドポイントで CSV を返却できる状態にする。

**共通参照**:

- `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.4 export
- `@docs/2.基本設計/API設計書.md` の §5.4 エクスポート
- `@docs/2.基本設計/非同期処理フロー設計書.md` の §2.2 エクスポートフロー
- `@docs/3.詳細設計/シーケンス図.md` の §4.1

---

- [ ] **Task 6.1**: ExportJob ドメインモデルと Repository の作成

  - **目的**: エクスポートジョブのドメインモデルとステータス遷移ルール、永続化インターフェースを定義する
  - **やること**:
    - `export/domain/model/ExportJob.java`:
      - フィールド: id, userId, status, totalReceipts, fileName, storageKey, errorMessage, createdAt, completedAt
      - ステータス遷移メソッド:
        - `markAsProcessing()` — QUEUED → PROCESSING
        - `complete(String fileName, String storageKey)` — PROCESSING → COMPLETED
        - `fail(String errorMessage)` — PROCESSING → FAILED
    - `export/domain/repository/ExportJobRepository.java`:
      - `save(ExportJob job)` — INSERT
      - `findByIdAndUserId(UUID id, String userId): Optional<ExportJob>`
      - `updateStatus(ExportJob job)` — ステータス更新
      - `saveReceiptLinks(UUID exportJobId, List<UUID> receiptIds)` — 中間テーブルへの INSERT
      - `findReceiptIdsByExportJobId(UUID exportJobId): List<UUID>` — 対象レシート ID 取得
  - **完了条件**:
    - ステータス遷移がドメインモデル内で制御されている
    - 中間テーブル操作が Repository インターフェースに含まれている
  - **参照**:
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §3.4 export_jobs, §3.5 export_job_receipts
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §4.2 エクスポートジョブステータス
  - **推定時間**: 30分

---

- [ ] **Task 6.2**: ExportJob MyBatis Mapper と RepositoryImpl の作成

  - **目的**: エクスポートジョブと中間テーブルの MyBatis 永続化層を実装する
  - **やること**:
    - `export/infrastructure/mybatis/ExportJobMapper.java` — `@Mapper` インターフェース
    - `src/main/resources/mapper/ExportJobMapper.xml`:
      - export_jobs の INSERT / SELECT / UPDATE
      - export_job_receipts の一括 INSERT（`<foreach>` 使用）
      - export_job_receipts から receipt_id の取得
    - `export/infrastructure/mybatis/ExportJobRepositoryImpl.java`:
      - `ExportJobRepository` を実装し、Mapper に委譲
  - **完了条件**:
    - エクスポートジョブの CRUD が動作する
    - 中間テーブルへの一括 INSERT が `<foreach>` で正しく動作する
  - **参照**:
    - `@docs/2.基本設計/DBスキーマ設計書.md` の §3.4, §3.5（テーブル定義）
  - **依存**: Task 6.1
  - **推定時間**: 45分

---

- [ ] **Task 6.3**: Export エラーコードの作成

  - **目的**: エクスポート固有のエラーコードを定義する
  - **やること**:
    - `export/domain/exception/ExportErrorCode.java` — enum:
      - `EXPORT_NOT_FOUND`（404, NOT_FOUND）
      - `EXPORT_NOT_READY`（409, CONFLICT）— 未完了時のダウンロード試行
      - `EXPORT_EMPTY_SELECTION`（400, VALIDATION）— 対象レシートなし
      - `EXPORT_PROCESSING_FAILED`（500, INTERNAL）— CSV 生成エラー
    - `export/domain/exception/ExportException.java` — `DomainException` サブクラス
  - **完了条件**:
    - 4つのエラーコードが定義されている
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §3.5（export エラーコード）
  - **依存**: Task 1.3
  - **推定時間**: 10分

---

- [ ] **Task 6.4**: CsvGenerator の作成

  - **目的**: レシートデータから CSV バイト列を生成するユーティリティを実装する
  - **やること**:
    - `export/application/CsvGenerator.java`:
      - `generate(List<Receipt> receipts): byte[]` — UTF-8 BOM 付き CSV を生成
      - ヘッダー行: `日付,金額,支払先,品目,税区分`
      - 各レシートのデータ行を出力
      - フィールド内のカンマ・改行・ダブルクォートのエスケープ処理
      - BOM（`\uFEFF`）を付与してExcelでの文字化けを防止
  - **完了条件**:
    - CSV が API 設計書 §5.4 のフォーマットに準拠している
    - カンマを含むフィールド（例: `コピー用紙 A4, 500枚`）が正しくエスケープされる
    - UTF-8 BOM が付与されている
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.4 CSV フォーマット
  - **推定時間**: 45分

---

- [ ] **Task 6.5**: ExportService の作成

  - **目的**: エクスポートジョブの作成・CSV 生成処理・ダウンロードのビジネスロジックを統合する
  - **やること**:
    - `export/application/ExportService.java`:
      - `createExport(List<UUID> receiptIds, String userId)`:
        1. receiptIds の存在確認（空ならバリデーションエラー）
        2. エクスポートジョブレコードを DB に INSERT（status=QUEUED）
        3. 中間テーブル（export_job_receipts）に対象レシート ID を INSERT
        4. SQS にメッセージ送信（exportId, userId）
        5. `ExportJobResponse` を返却
      - `processExport(ExportJobMessage message)`:
        1. ステータスを PROCESSING に更新
        2. 中間テーブルから対象 receipt_id を取得
        3. `ReceiptService.findByIds()` でレシートデータ取得
        4. `CsvGenerator.generate()` を `CompletableFuture.get(300, SECONDS)` でタイムアウト制御
        5. CSV を MinIO に保存（storageKey: `exports/{exportId}/{fileName}`）
        6. ステータスを COMPLETED に更新、fileName / storageKey を記録
        7. 失敗時: ステータスを FAILED に更新、例外を再スロー
      - `getExportStatus(UUID exportId, String userId)` — ステータス取得
      - `downloadExport(UUID exportId, String userId)`:
        - ステータスが COMPLETED でなければ `EXPORT_NOT_READY`
        - MinIO から CSV を取得して返却
    - `@Transactional` でトランザクション管理
  - **完了条件**:
    - エクスポートジョブ作成 → ワーカー処理 → CSV 生成 → MinIO 保存の一連のフローが動作する
    - 300秒タイムアウト時に FAILED に遷移し、例外が再スローされる
    - 空の receiptIds でバリデーションエラーが返る
    - 未完了のエクスポートのダウンロードが `EXPORT_NOT_READY` で拒否される
  - **参照**:
    - `@docs/3.詳細設計/シーケンス図.md` の §4.1（エクスポートシーケンス）
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §2.2 エクスポートフロー, §5.2（300秒タイムアウト）
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §8.2 エクスポートワーカー
  - **依存**: Task 6.1, Task 6.2, Task 6.3, Task 6.4, Task 5.4（ReceiptService）, Task 2.1, Task 2.2
  - **推定時間**: 1時間45分

---

- [ ] **Task 6.6**: ExportJobMessageListener の作成

  - **目的**: SQS からのエクスポートジョブメッセージを受信し、Service に処理を委譲する
  - **やること**:
    - `export/infrastructure/messaging/ExportJobMessage.java` — record:
      - exportId (UUID), userId (String)
    - `export/infrastructure/messaging/ExportJobMessageListener.java`:
      - `@SqsListener("receipt-export-queue")` でメッセージ受信
      - `ExportService.processExport(message)` に委譲
      - 例外発生時はログ出力後に再スロー
  - **完了条件**:
    - SQS にメッセージが投入されるとリスナーが受信する
    - 処理成功時にメッセージが自動削除される
  - **参照**:
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §4.3（ポーリング方式）
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §6.4
  - **依存**: Task 6.5
  - **推定時間**: 20分

---

- [ ] **Task 6.7**: ExportController と DTO の作成

  - **目的**: エクスポートの3つの API エンドポイントを実装する
  - **やること**:
    - `export/presentation/dto/request/CreateExportRequest.java` — record + Bean Validation:
      - receiptIds (List<UUID>, @NotEmpty)
    - `export/presentation/dto/response/ExportJobResponse.java` — record:
      - exportId, status, totalReceipts, fileName, createdAt, completedAt
    - `export/presentation/ExportController.java`:
      - `POST /api/exports` — @Valid + 201 Created
      - `GET /api/exports/{exportId}` — 200 OK
      - `GET /api/exports/{exportId}/download` — CSV ファイルをレスポンスボディで返却
        - Content-Type: `text/csv; charset=UTF-8`
        - Content-Disposition: `attachment; filename="receipts_YYYYMMDD_HHmmss.csv"`
  - **完了条件**:
    - エクスポートジョブ作成 → ステータス取得 → CSV ダウンロードの一連のフローが動作する
    - ダウンロード API が正しい Content-Type / Content-Disposition ヘッダーを返す
    - 未完了のダウンロードが 409 で拒否される
  - **参照**:
    - `@docs/2.基本設計/API設計書.md` の §5.4（リクエスト/レスポンス例、CSV フォーマット）
    - `@docs/3.詳細設計/バックエンドクラス設計書.md` の §5.4
  - **依存**: Task 6.5
  - **推定時間**: 45分

---

### Phase 7: 結合・仕上げ

**この Phase のゴール**:
全モジュールを通した一連のフロー（アップロード → OCR → 確認 → 一覧 → エクスポート）が
端から端まで動作し、エラーハンドリングが完備した状態にする。

**共通参照**:

- `@docs/3.詳細設計/シーケンス図.md` - 全フロー
- `@docs/3.詳細設計/エラーハンドリング設計書.md` - エラーハンドリング全体

---

- [ ] **Task 7.1**: docker-compose への LocalStack 追加

  - **目的**: ローカル開発環境で SQS を利用できるようにする
  - **やること**:
    - `docker-compose.yml` に LocalStack コンテナを追加:
      - イメージ: `localstack/localstack`
      - ポート: 4566
      - SQS のみ有効化（`SERVICES=sqs`）
    - SQS キューの初期化スクリプト作成:
      - `receipt-ocr-queue` + DLQ（`receipt-ocr-queue-dlq`）
      - `receipt-export-queue` + DLQ（`receipt-export-queue-dlq`）
      - DLQ の `maxReceiveCount=3` を RedrivePolicy で設定
    - MinIO の `receipt-uploads` バケット自動作成の確認
  - **完了条件**:
    - `docker-compose up` で LocalStack が起動し、4つの SQS キューが自動作成される
    - Spring Boot アプリケーションから LocalStack の SQS に接続できる
  - **参照**:
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §3（SQS キュー定義）, §7（ローカル開発環境）
  - **推定時間**: 45分

---

- [ ] **Task 7.2**: Tesseract OCR の Docker 統合

  - **目的**: Docker コンテナ内で Tesseract OCR が動作するようにする
  - **やること**:
    - Dockerfile に Tesseract のインストールを追加:
      - `tesseract-ocr` パッケージ
      - `tesseract-ocr-jpn`（日本語学習データ）
    - `application.yml` に tessdata パスの設定
    - Tess4J の動作確認（サンプル画像で OCR テスト）
  - **完了条件**:
    - Docker コンテナ内で日本語の画像から OCR テキストが取得できる
    - tessdata のパスが正しく設定されている
  - **参照**:
    - `@docs/1.要件定義/要件定義書.md` の §4.1 Tesseract OCR
  - **推定時間**: 1時間

---

- [ ] **Task 7.3**: 単体テスト（Domain / Service / Mapper）の整備

  - **目的**: 回帰しやすいビジネスルールと永続化ロジックを
    結合テスト前に検証できる状態を作る
  - **やること**:
    - Domain モデルの状態遷移テスト:
      - `OcrJob`（QUEUED→PROCESSING→COMPLETED→CONFIRMED, 不正遷移）
      - `ExportJob`（QUEUED→PROCESSING→COMPLETED / FAILED）
    - Service テスト（モックベース）:
      - `UploadBatchService` のファイルバリデーション（枚数・サイズ・形式）
      - `OcrJobService` の確定・リトライの状態チェック
      - `ExportService` の空選択エラー / 未完了ダウンロードエラー
    - Mapper テスト（DB 連携）:
      - `ReceiptMapper` の動的 SQL（フィルタ・ソート・ページング）
      - `OcrJobMapper.markAsProcessingIfQueued` の楽観ロック競合
  - **完了条件**:
    - 主要ドメイン遷移と主要ユースケースのテストが自動実行で通る
    - 動的 SQL と楽観ロックの回帰を検知できる
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §6.2, §6.3
    - `@docs/2.基本設計/非同期処理フロー設計書.md` の §5.4（冪等性）
  - **依存**: Phase 3, Phase 4, Phase 5, Phase 6
  - **推定時間**: 2時間

---

- [ ] **Task 7.4**: フロー①の結合テスト

  - **目的**: アップロード → OCR → 確認・確定のフロー全体が正しく動作することを確認する
  - **やること**:
    - テストシナリオ:
      1. JPEG ファイルをアップロード → バッチ作成 → SQS 投入
      2. ワーカーが OCR 実行 → COMPLETED に遷移
      3. ポーリング（2秒間隔）でステータスが COMPLETED になることを確認
      4. OCR 結果の取得 + 署名付き URL の取得
      5. 確定 → レシート作成 → ジョブが CONFIRMED に遷移
    - エラーケースのテスト:
      - ファイルバリデーションエラー（サイズ超過、形式不正）
      - PDF ファイルの OCR（PDF → 画像変換 → OCR）
      - 不正なステータス遷移（QUEUED ジョブの確定試行）
    - 発見した不具合の修正
  - **完了条件**:
    - JPEG / PNG / PDF のアップロードから確定までのフローが正常動作する
    - 各エラーケースで適切なエラーレスポンスが返る
  - **参照**:
    - `@docs/3.詳細設計/シーケンス図.md` の §2, §3
  - **依存**: Task 7.3
  - **推定時間**: 2時間

---

- [ ] **Task 7.5**: フロー②の結合テスト

  - **目的**: レシート一覧 → エクスポートのフロー全体が正しく動作することを確認する
  - **やること**:
    - テストシナリオ:
      1. レシート一覧の取得（フィルタ・ソート・ページング）
      2. レシートの編集 → 更新確認
      3. レシートの削除 → 一覧から消えることを確認
      4. レシート選択 → エクスポート → ポーリング（2秒間隔）→ CSV ダウンロード
      5. CSV の内容が正しいことを確認
    - エラーケースのテスト:
      - 存在しないレシートの操作
      - 未完了エクスポートのダウンロード
    - 発見した不具合の修正
  - **完了条件**:
    - レシート CRUD が正常動作する
    - エクスポートから CSV ダウンロードまでのフローが正常動作する
    - CSV の内容が API 設計書のフォーマットに準拠している
  - **参照**:
    - `@docs/3.詳細設計/シーケンス図.md` の §4
    - `@docs/2.基本設計/API設計書.md` の §5.4 CSV フォーマット
  - **依存**: Task 7.3
  - **推定時間**: 1時間30分

---

- [ ] **Task 7.6**: ログ出力の整備

  - **目的**: 障害調査に必要な情報をログに出力し、可観測性を確保する
  - **やること**:
    - 各 Service / MessageListener に SLF4J ロガーを追加
    - ログ出力ポイント:
      - INFO: ジョブの開始・完了、レシートの確定、エクスポートの完了
      - WARN: リトライ発生、冪等性チェックによるスキップ
      - ERROR: OCR 処理の失敗（スタックトレース含む）、外部サービスの障害
    - ログメッセージにジョブ ID / ユーザー ID を含める
    - `application.yml` のログレベル設定を見直し
  - **完了条件**:
    - 正常系の主要イベントが INFO ログに出力される
    - エラー発生時にジョブ ID とスタックトレースが ERROR ログに出力される
    - リトライ発生時に WARN ログが出力される
  - **参照**:
    - `@docs/3.詳細設計/エラーハンドリング設計書.md` の §9 ログ出力方針
  - **推定時間**: 45分

---

## ✅ 確定した設計判断

### 1. MyBatis の採用（JPA から移行）

- [x] MyBatis を ORM として採用
  - → **確定**: SQL ベースのデータアクセスを学習テーマとする
  - → `spring-boot-starter-data-jpa` を削除し、`mybatis-spring-boot-starter` に置き換え

**理由:**

- 動的 SQL（フィルタ・ソート）の実装が XML の `<if>`, `<where>` で直感的
- SQL の実行計画を意識したクエリ設計が学べる

### 2. Repository + Mapper の2層構造

- [x] Mapper を直接 Service から使わず、Repository インターフェースを挟む → **確定**

**理由:**

- ドメイン層が MyBatis に依存しない（依存性逆転）
- Repository をモックすれば Service の単体テストが容易

### 3. OCR 結果のインライン格納

- [x] `ocr_results` テーブルに分離せず、`ocr_jobs` テーブルにインラインで格納 → **確定**

**理由:**

- ocr_jobs と OCR 結果は完全に 1:1 で常に同時参照する
- JOIN 不要で 1 クエリで全情報を取得でき、MyBatis の ResultMap がシンプル

### 4. ワーカーの API 同居方式

- [x] OCR ワーカー・エクスポートワーカーを Spring Boot API と同一プロセスに同居 → **確定**

**理由:**

- 学習プロジェクトとして構成をシンプルに保つ
- SQS リスナーは Spring Cloud AWS の `@SqsListener` で宣言的に実装

### 5. SQS リトライの委譲方式

- [x] リトライを SQS の再配信メカニズムに全面委譲 → **確定**

**理由:**

- アプリ側のリトライロジックが不要になり実装がシンプル
- DLQ への移動で最終的な失敗を確実にキャッチ

---

## 📅 マイルストーン

- **Phase 1**: プロジェクト基盤整備（1日）
- **Phase 2**: 横断的インフラ（1〜2日）
- **Phase 3**: アップロードバッチ機能（2〜3日）
- **Phase 4**: OCR ジョブ機能（3〜4日）
- **Phase 5**: レシート機能（2〜3日）
- **Phase 6**: エクスポート機能（2〜3日）
- **Phase 7**: 結合・仕上げ（3〜4日）

**合計推定**: 14〜21日

---

## 📚 参考ドキュメント

- `@docs/1.要件定義/要件定義書.md` - 要件定義書（機能要件・非機能要件・技術スタック）
- `@docs/2.基本設計/API設計書.md` - API 設計書（エンドポイント・レスポンス形式・ステータス定義）
- `@docs/2.基本設計/DBスキーマ設計書.md` - DB スキーマ設計書（テーブル定義・ステータス遷移・設計判断）
- `@docs/2.基本設計/非同期処理フロー設計書.md` - 非同期処理フロー設計書（SQS・ワーカー・リトライ設計）
- `@docs/3.詳細設計/バックエンドクラス設計書.md` - パッケージ・クラス設計書（モジュール構成・レイヤー規約・MyBatis パターン）
- `@docs/3.詳細設計/シーケンス図.md` - シーケンス図（全フローの処理順序）
- `@docs/3.詳細設計/エラーハンドリング設計書.md` - エラーハンドリング設計書（例外体系・エラーコード・レイヤー別方針）

---

**作成日**: 2026-03-01
**最終更新**: 2026-03-02
