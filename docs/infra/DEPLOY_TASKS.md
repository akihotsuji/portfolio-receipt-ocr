# 領収書 OCR - 本番環境デプロイ タスクリスト

各ステップには動作確認を含みます。「確認」に書かれた内容が全て OK になってから次へ進んでください。

> **前提:**
> SUNABA プロジェクトの共有インフラ（VPC、RDS、CloudFront、API Gateway 等）は構築済みの状態から開始します。
> 本タスクリストでは、receipt-ocr 固有のリソース追加とアプリケーションデプロイを段階的に行います。
>
> **全体の流れ:**
> Step 0 で前提を確認し、Step 1〜6 で Terraform を使って receipt-ocr 固有の AWS リソースを構築します。
> Step 7〜8 でアプリケーションコードを本番用に調整し、Step 9〜11 で実際にデプロイ。
> Step 12 で動作確認、Step 13 で運用スクリプトの更新、Step 14 で CI/CD を構築します。

---

## 既存インフラとの関係

```
[既存 SUNABA インフラ（構築済み）]
├── VPC / Subnet / Security Group / Cloud Map
├── SSM Parameter Store（DB パスワード、JWT 鍵）
├── RDS PostgreSQL（sunaba-db）
├── ECR（portal, habittracker）
├── ECS Fargate Cluster（sunaba-cluster）
├── API Gateway HTTP API + VPC Link
├── CloudFront + S3（フロントエンド配信）
└── GitHub OIDC（CI/CD 認証）

[receipt-ocr で追加するリソース]
├── ECR リポジトリ（sunaba-receiptocr-backend）  ← terraform.tfvars に追加で自動作成
├── ECS サービス / タスク定義                      ← 同上
├── API Gateway ルート（/api/receiptocr/*）        ← 同上
├── CloudFront パス（/receiptocr/*）               ← 同上
├── SQS キュー × 4（OCR + DLQ、Export + DLQ）     ← 新規 Terraform モジュール
├── S3 バケット（receipt-uploads）                  ← 新規 Terraform モジュール
├── Lambda 関数（export-csv）                       ← SAM でデプロイ
└── IAM ポリシー拡張（ECS → SQS/S3 アクセス）     ← 既存モジュールの拡張
```

---

## Step 0: 前提確認

> **目的:** receipt-ocr のデプロイに必要な前提条件が整っていることを確認します。
> 既存の SUNABA インフラが正常に動作していること、receipt-ocr 固有のツールが揃っていることを検証します。

### 0-1. 既存インフラの稼働確認

- [ ] ECS クラスターが存在し、既存サービスが動作していること

```bash
aws ecs describe-clusters --clusters sunaba-cluster \
  --query 'clusters[0].{Name:clusterName,Status:status}' --output table
```

- [ ] RDS が稼働中であること

```bash
aws rds describe-db-instances --db-instance-identifier sunaba-db \
  --query 'DBInstances[0].DBInstanceStatus' --output text
# available が表示される
```

- [ ] API Gateway が存在すること

```bash
aws apigatewayv2 get-apis \
  --query 'Items[?Name==`sunaba-api`].{Name:Name,Endpoint:ApiEndpoint}' --output table
```

### 0-2. 追加ツールの確認

> receipt-ocr は Lambda デプロイに SAM CLI を使用します。

- [ ] SAM CLI がインストールされていること

```bash
sam --version
# SAM CLI, version 1.x.x 以上
```

> **未インストールの場合:**
>
> ```bash
> # Windows (Chocolatey)
> choco install aws-sam-cli
>
> # またはインストーラーからダウンロード
> # https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html
> ```

### 0-3. receipt-ocr のローカル開発環境確認

- [ ] バックエンドのビルドが通ること

```bash
cd ~/Desktop/portfolio/receipt-ocr/backend
./mvnw compile
```

- [ ] フロントエンドのビルドが通ること

```bash
cd ~/Desktop/portfolio/receipt-ocr/frontend
npm run build
```

- [ ] Lambda プロジェクトのビルドが通ること

```bash
cd ~/Desktop/portfolio/receipt-ocr/lambda/export-csv
sam build
```

---

## Step 1: terraform.tfvars に receiptocr を追加

> **目的:** 既存の Terraform モジュール構成により、`terraform.tfvars` の `apps` マップにエントリを追加するだけで
> ECR リポジトリ、ECS サービス/タスク定義、API Gateway ルート、CloudFront パスが自動的に作成されます。

### 1-1. terraform.tfvars の更新

- [ ] `infrastructure/terraform/terraform.tfvars` の `apps` に `receiptocr` を追加

```hcl
apps = {
  portal = {
    has_backend  = true
    has_frontend = true
    has_database = true
  }
  habittracker = {
    has_backend  = true
    has_frontend = true
    has_database = true
  }
  # コメントアウトを外して追加
  receiptocr = {
    has_backend  = true
    has_frontend = true
    has_database = true
  }
}
```

### 1-2. Plan で追加リソースを確認

> **何が作られるか:**
>
> - ECR リポジトリ: `sunaba-receiptocr-backend`
> - ECS タスク定義 + サービス: `sunaba-receiptocr-backend`
> - Cloud Map サービス: `receiptocr-backend.sunaba.local`
> - API Gateway ルート: `ANY /api/receiptocr/{proxy+}`
> - CloudFront パス: `/receiptocr/*` → S3
> - CloudWatch Log Group: `/ecs/sunaba/receiptocr-backend`

- [ ] `terraform plan` で追加リソースを確認

```bash
cd ~/Desktop/portfolio/infrastructure/terraform
terraform plan
```

> 10〜15 個程度のリソースが追加される。既存リソースの変更がないことも確認する。

### 1-3. Apply（まだ実行しない）

> **注意:** この段階ではまだ apply しません。
> Step 2〜4 で SQS / S3 / IAM の Terraform モジュールを追加してから、まとめて apply します。
> 理由: ECS タスク定義が SQS / S3 へのアクセス権限を必要とするため、権限なしで起動するとエラーになります。

---

## Step 2: SQS キュー Terraform モジュールの作成

> **目的:** OCR ジョブキューとエクスポートキューを Terraform で管理します。
> 各キューにデッドレターキュー（DLQ）を設定し、3回失敗したメッセージを DLQ に移動させます。
>
> **作成するキュー:**
>
> | キュー名                    | 用途              | DLQ                             |
> | --------------------------- | ----------------- | ------------------------------- |
> | `receipt-ocr-queue`         | OCR ジョブ処理    | `receipt-ocr-queue-dlq`         |
> | `receipt-export-queue`      | CSV エクスポート   | `receipt-export-queue-dlq`      |

### 2-1. SQS モジュールの作成

- [ ] `infrastructure/terraform/modules/sqs/` ディレクトリを作成
- [ ] `modules/sqs/variables.tf` を作成

```hcl
variable "project_name" {
  type = string
}

variable "queues" {
  description = "SQS キュー定義"
  type = map(object({
    visibility_timeout_seconds = number
    max_receive_count          = number
  }))
}
```

- [ ] `modules/sqs/main.tf` を作成
  - 各キューのメインキュー + DLQ を作成
  - Visibility Timeout: メインキューに設定（OCR: 120秒）
  - RedrivePolicy: `maxReceiveCount` 回失敗で DLQ に移動
  - DLQ の保持期間: 14日（障害調査用）

- [ ] `modules/sqs/outputs.tf` を作成
  - 各キューの ARN と URL を出力

### 2-2. main.tf に SQS モジュールを追加

- [ ] `infrastructure/terraform/main.tf` に SQS モジュールの呼び出しを追加

```hcl
module "sqs" {
  source       = "./modules/sqs"
  project_name = var.project_name

  queues = {
    "receipt-ocr" = {
      visibility_timeout_seconds = 120
      max_receive_count          = 3
    }
    "receipt-export" = {
      visibility_timeout_seconds = 120
      max_receive_count          = 3
    }
  }
}
```

---

## Step 3: レシートストレージ S3 バケット Terraform モジュールの作成

> **目的:** アップロードされた領収書画像とエクスポート CSV を保存する S3 バケットを作成します。
> フロントエンド配信用の既存 S3 バケットとは分離して管理します。
>
> **バケット構成:**
>
> ```
> sunaba-receipt-uploads/
> ├── uploads/{batchId}/{fileName}      ← アップロードされた領収書画像
> ├── converted/{jobId}/{fileName}      ← PDF → 画像変換後のファイル
> └── exports/{exportId}/{fileName}.csv ← エクスポート CSV
> ```

### 3-1. receipt-storage モジュールの作成

- [ ] `infrastructure/terraform/modules/receipt-storage/` ディレクトリを作成
- [ ] `modules/receipt-storage/variables.tf` を作成

```hcl
variable "project_name" {
  type = string
}

variable "bucket_name" {
  type    = string
  default = "receipt-uploads"
}
```

- [ ] `modules/receipt-storage/main.tf` を作成
  - S3 バケット作成（`sunaba-receipt-uploads`）
  - パブリックアクセスブロック（全て有効）
  - バケットポリシー（ECS タスクロールと Lambda ロールからのみアクセス許可）
  - ライフサイクルルール（uploads/ のオブジェクトを90日後に削除 — ポートフォリオ用途のためコスト削減）
  - バージョニングは無効（ストレージコスト削減）

- [ ] `modules/receipt-storage/outputs.tf` を作成
  - バケット名と ARN を出力

### 3-2. main.tf に receipt-storage モジュールを追加

- [ ] `infrastructure/terraform/main.tf` にモジュール呼び出しを追加

```hcl
module "receipt_storage" {
  source       = "./modules/receipt-storage"
  project_name = var.project_name
}
```

---

## Step 4: IAM ポリシーの拡張（ECS → SQS / S3 アクセス）

> **目的:** receipt-ocr の ECS タスクが SQS キューの読み書きとレシートストレージ S3 バケットへのアクセスを行えるよう、
> 既存の ECS モジュールの IAM ポリシーを拡張します。
>
> **必要な権限:**
>
> | アクション             | リソース                     | 用途                           |
> | ---------------------- | ---------------------------- | ------------------------------ |
> | `sqs:SendMessage`      | receipt-ocr-queue             | OCR ジョブ投入                 |
> | `sqs:SendMessage`      | receipt-export-queue          | エクスポートジョブ投入         |
> | `sqs:ReceiveMessage`   | receipt-ocr-queue             | OCR ワーカーのメッセージ受信   |
> | `sqs:DeleteMessage`    | receipt-ocr-queue             | 処理完了後のメッセージ削除     |
> | `sqs:GetQueueUrl`      | receipt-ocr-queue             | キュー URL の取得              |
> | `sqs:GetQueueAttributes` | receipt-ocr-queue           | キュー属性の取得               |
> | `s3:PutObject`         | sunaba-receipt-uploads/*      | 画像 / CSV のアップロード      |
> | `s3:GetObject`         | sunaba-receipt-uploads/*      | 画像の取得                     |
> | `s3:DeleteObject`      | sunaba-receipt-uploads/*      | 画像の削除                     |

### 4-1. ECS タスクロールのポリシー拡張

- [ ] `infrastructure/terraform/modules/ecs/main.tf` のタスクロールに SQS / S3 ポリシーを追加
  - 条件分岐: `receiptocr` アプリの場合のみ SQS / S3 ポリシーをアタッチ
  - または、全アプリ共通の追加ポリシーとして設計（将来の拡張を考慮）

### 4-2. ECS タスク定義の環境変数追加

- [ ] `infrastructure/terraform/modules/ecs/main.tf` のタスク定義に receipt-ocr 固有の環境変数を追加
  - アプリごとの条件分岐、または `app_config` 変数による柔軟な設定

```
# receipt-ocr 固有の環境変数
SQS_OCR_QUEUE_URL        = module.sqs.queue_urls["receipt-ocr"]
SQS_EXPORT_QUEUE_URL     = module.sqs.queue_urls["receipt-export"]
RECEIPT_STORAGE_BUCKET    = module.receipt_storage.bucket_name
SPRING_PROFILES_ACTIVE    = prod
```

### 4-3. セキュリティグループの確認

- [ ] 既存の ECS セキュリティグループで SQS / S3 への通信が可能であることを確認
  - SQS / S3 はリージョナルエンドポイント（`*.amazonaws.com`）にアクセス
  - ECS タスクが Public Subnet にあり、IGW 経由でインターネットアクセス可能 → 追加設定不要

---

## Step 5: Terraform の一括適用

> **目的:** Step 1〜4 で準備した Terraform の変更を一括で適用します。
> receipt-ocr の基本インフラ（ECR、ECS、SQS、S3）が全て構築されます。

### 5-1. Plan で全追加リソースを確認

- [ ] `terraform plan` を実行し、追加されるリソースの一覧を確認

```bash
cd ~/Desktop/portfolio/infrastructure/terraform
terraform plan
```

> **想定される追加リソース（20〜30 個）:**
>
> - ECR: リポジトリ × 1
> - ECS: タスク定義 × 1、サービス × 1、Cloud Map サービス × 1
> - SQS: キュー × 4（メイン × 2 + DLQ × 2）
> - S3: バケット × 1 + ポリシー
> - IAM: ポリシー追加
> - API Gateway: ルート × 1、統合 × 1
> - CloudWatch: ロググループ × 1

### 5-2. Apply

- [ ] `terraform apply` を実行

```bash
terraform apply
```

### 5-3. 確認

```bash
# ECR リポジトリが作成されたこと
aws ecr describe-repositories \
  --query 'repositories[?contains(repositoryName, `receiptocr`)].repositoryName' --output text
# sunaba-receiptocr-backend が表示される

# SQS キューが作成されたこと
aws sqs list-queues --queue-name-prefix receipt \
  --query 'QueueUrls' --output table
# receipt-ocr-queue, receipt-ocr-queue-dlq,
# receipt-export-queue, receipt-export-queue-dlq が表示される

# S3 バケットが作成されたこと
aws s3 ls | grep receipt-uploads
# sunaba-receipt-uploads が表示される

# ECS サービスが作成されたこと（タスク数は 0 でOK。イメージ未 push のため）
aws ecs list-services --cluster sunaba-cluster \
  --query 'serviceArns[?contains(@, `receiptocr`)]' --output text
```

- [ ] AWS コンソールで SQS キューの設定を目視確認
  - Visibility Timeout: 120秒
  - DLQ の maxReceiveCount: 3
  - DLQ の保持期間: 14日

---

## Step 6: Lambda 用 Terraform リソースの作成

> **目的:** エクスポート CSV 生成用の Lambda 関数のインフラを Terraform で管理します。
> Lambda 関数本体は SAM でデプロイしますが、IAM ロールと SQS イベントソースマッピングの基盤は Terraform で構築します。
>
> **Lambda のアーキテクチャ:**
>
> ```
> receipt-export-queue → SQS イベントソースマッピング → Lambda (ExportCsvHandler)
>                                                          ├── DB: ステータス更新 + レシート取得
>                                                          ├── CSV 生成
>                                                          └── S3: CSV 保存
> ```

### 6-1. Lambda モジュールの作成（または SAM テンプレートで管理）

> **設計判断:** Lambda のデプロイには2つのアプローチがあります。
>
> | アプローチ               | メリット                                    | デメリット                         |
> | ------------------------ | ------------------------------------------- | ---------------------------------- |
> | **Terraform で管理**     | 他リソースと一元管理できる                  | Java Lambda のビルド統合が複雑     |
> | **SAM で管理（推奨）**   | Java ビルド・パッケージングの統合がスムーズ  | Terraform と管理が分散する         |
>
> → **SAM で Lambda をデプロイし、Terraform は IAM ロールのみ管理する** ハイブリッドアプローチを採用

- [ ] Lambda 実行ロールを Terraform で作成（`infrastructure/terraform/modules/lambda-export/`）
  - 信頼ポリシー: `lambda.amazonaws.com`
  - マネージドポリシー: `AWSLambdaBasicExecutionRole`
  - インラインポリシー:
    - SQS: `receipt-export-queue` の読み取り・削除
    - S3: `sunaba-receipt-uploads` への読み書き
    - RDS: VPC 内からの DB 接続（VPC Lambda の場合）
    - SSM: DB パスワードの読み取り
    - CloudWatch Logs: ログ出力

- [ ] `main.tf` にモジュール呼び出しを追加
- [ ] `terraform plan` → `terraform apply`

**確認:**

```bash
# Lambda 実行ロールが作成されたこと
aws iam get-role --role-name sunaba-export-csv-lambda-role \
  --query 'Role.RoleName' --output text
```

### 6-2. SAM テンプレートの本番設定

- [ ] `lambda/export-csv/template.yaml` に本番環境の設定を追加

```yaml
Resources:
  ExportCsvFunction:
    Type: AWS::Serverless::Function
    Properties:
      Handler: com.portfolio.receiptocr.lambda.exportcsv.ExportCsvHandler::handleRequest
      Runtime: java21
      MemorySize: 512
      Timeout: 300
      ReservedConcurrentExecutions: 3
      Role: !Sub arn:aws:iam::${AWS::AccountId}:role/sunaba-export-csv-lambda-role
      Environment:
        Variables:
          DB_HOST: !Ref DbHost
          DB_NAME: sunaba
          DB_SCHEMA: receipt_ocr
          DB_USERNAME: sunaba_admin
          DB_PASSWORD_SSM_KEY: /sunaba/rds/password
          S3_BUCKET: sunaba-receipt-uploads
          AWS_REGION: ap-northeast-1
      Events:
        SQSEvent:
          Type: SQS
          Properties:
            Queue: !Sub arn:aws:sqs:ap-northeast-1:${AWS::AccountId}:receipt-export-queue
            BatchSize: 1
```

> **注意:** Lambda が RDS にアクセスするため、VPC 設定が必要な場合があります。
> RDS が Isolated Subnet にあるため、Lambda も VPC 内で実行する必要があります。
> その場合、Lambda に VPC 設定と NAT Gateway（またはVPCエンドポイント）が必要です。
>
> **YAGNI アプローチ（推奨）:**
> ポートフォリオ用途では NAT Gateway（月額 ~$32）は高コスト。
> 代替案として RDS をパブリックサブネットからアクセス可能にするか、
> VPC Lambda + VPC エンドポイント（S3, SQS, SSM）の構成を検討する。
> → **Step 6 の時点で設計判断を行い、方針を決定してから進む**

---

## Step 7: アプリケーションの本番対応（バックエンド）

> **目的:** receipt-ocr のバックエンドを AWS 上で動くように調整します。
>
> **開発環境 vs 本番環境の主な違い:**
>
> | 項目              | 開発環境                        | 本番環境                                                  |
> | ----------------- | ------------------------------- | --------------------------------------------------------- |
> | API パス          | `/api`                          | `/api/receiptocr`（CloudFront ルーティング）               |
> | ストレージ        | MinIO（localhost:9000）          | S3（sunaba-receipt-uploads）                               |
> | メッセージキュー  | LocalStack（localhost:4566）     | Amazon SQS                                                 |
> | OCR エンジン      | ローカル Tesseract               | Docker コンテナ内 Tesseract                                |
> | DB 接続           | localhost:5432                   | RDS エンドポイント（環境変数で注入）                       |
> | 認証              | JWT 検証（ローカル JWKS）        | JWT 検証（Portal API の JWKS エンドポイント経由）          |

### 7-1. Dockerfile の作成（Tesseract OCR 統合）

> **ポイント:** receipt-ocr の Docker イメージには Tesseract OCR と日本語学習データを含める必要があります。
> portal / habittracker の Dockerfile をベースに、Tesseract のインストールを追加します。

- [ ] `receipt-ocr/backend/Dockerfile` を作成
  - **マルチステージビルド**: ビルドステージ（JDK + Maven）→ 実行ステージ（JRE + Tesseract）
  - **Tesseract インストール**: `tesseract-ocr` + `tesseract-ocr-jpn`（日本語学習データ）
  - **非 root ユーザー**: セキュリティのため `appuser` で実行
  - **JVM 設定**: `-XX:+UseContainerSupport` でコンテナのメモリ制限を認識
  - **HEALTHCHECK**: `/actuator/health` エンドポイント

```dockerfile
# ビルドステージ
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN ./mvnw package -DskipTests

# 実行ステージ
FROM eclipse-temurin:21-jre-alpine
# Tesseract OCR + 日本語学習データのインストール
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-jpn
# アプリケーション設定
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/receiptocr/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
```

> **注意:** Alpine ベースイメージでの Tesseract 可用性を確認すること。
> Alpine のパッケージリポジトリに `tesseract-ocr-data-jpn` がない場合は、
> Debian ベースイメージ（`eclipse-temurin:21-jre`）を使用するか、
> traineddata ファイルを手動でダウンロードして COPY する。

- [ ] ローカルでビルドテスト

```bash
cd ~/Desktop/portfolio/receipt-ocr/backend
docker build --platform linux/amd64 -t sunaba-receiptocr-backend .
```

**確認:** ビルドが成功する（`Successfully tagged` が表示される）

### 7-2. application-prod.yml の作成

- [ ] `receipt-ocr/backend/src/main/resources/application-prod.yml` を作成

```yaml
server:
  servlet:
    context-path: /api/receiptocr
  forward-headers-strategy: framework

spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/sunaba?currentSchema=receipt_ocr
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2
  flyway:
    schemas: receipt_ocr

# MyBatis
mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

# SQS（本番は AWS の実 SQS を使用）
spring.cloud.aws:
  region:
    static: ap-northeast-1
  sqs:
    endpoint: # 本番では空（AWS のデフォルトエンドポイントを使用）

# ストレージ（本番は S3 を使用）
app:
  storage:
    bucket: ${RECEIPT_STORAGE_BUCKET}
    endpoint: # 本番では空（AWS のデフォルトエンドポイントを使用）
  sqs:
    ocr-queue: ${SQS_OCR_QUEUE_NAME:receipt-ocr-queue}
    export-queue: ${SQS_EXPORT_QUEUE_NAME:receipt-export-queue}
  ocr:
    tessdata-path: /usr/share/tessdata

# JWT 検証（Portal の JWKS から公開鍵を取得）
jwt:
  issuer: ${JWT_ISSUER:https://sunaba-project.com}
  jwks-uri: ${JWT_JWKS_URI:http://portal-backend.sunaba.local:8080/api/portal/.well-known/jwks.json}
  required-app: receiptocr

# ログ
logging:
  level:
    com.portfolio.receiptocr: INFO
    org.mybatis: WARN
```

### 7-3. ECS タスク定義の環境変数整理

> receipt-ocr の ECS タスクに注入する環境変数の一覧です。
> Terraform の ECS モジュールで設定します。

| 環境変数                | 値の取得元          | 説明                             |
| ----------------------- | ------------------- | -------------------------------- |
| `SPRING_PROFILES_ACTIVE`| 固定値: `prod`      | Spring Boot のプロファイル        |
| `DB_HOST`               | RDS エンドポイント  | DB 接続先                         |
| `DB_USERNAME`           | 固定値              | DB ユーザー名                     |
| `DB_PASSWORD`           | SSM SecureString    | DB パスワード（SSM から注入）     |
| `RECEIPT_STORAGE_BUCKET`| S3 バケット名       | レシートストレージバケット名      |
| `SQS_OCR_QUEUE_NAME`   | SQS キュー名       | OCR ジョブキュー名                |
| `SQS_EXPORT_QUEUE_NAME`| SQS キュー名       | エクスポートジョブキュー名        |
| `JWT_ISSUER`            | 固定値              | JWT 発行者の検証値                |
| `JWT_JWKS_URI`          | Cloud Map 経由      | Portal の JWKS エンドポイント     |

---

## Step 8: アプリケーションの本番対応（フロントエンド）

> **目的:** receipt-ocr のフロントエンドを本番環境の API パスに対応させます。

### 8-1. axios.ts の本番対応

- [ ] `VITE_API_BASE_URL` で API ベース URL を切り替え可能にする
  - 開発: `/api`（デフォルト、Vite proxy → localhost:8080）
  - 本番: `/api/receiptocr`（CloudFront → API Gateway → ECS）

- [ ] `VITE_PORTAL_API_BASE_URL` で Portal API ベース URL を切り替え可能にする
  - 開発: `/api`（デフォルト）
  - 本番: `/api/portal`（JWT リフレッシュ用）

### 8-2. ローカル開発の動作確認

- [ ] 環境変数なしでローカル開発が壊れていないことを確認

```bash
cd ~/Desktop/portfolio/receipt-ocr/frontend
npm run dev
```

---

## Step 9: バックエンドのデプロイ

> **目的:** receipt-ocr のバックエンドを Docker イメージにビルドして ECR に push し、ECS で起動させます。
>
> **デプロイの流れ:**
>
> 1. ECR にログイン
> 2. Docker イメージをビルド → ECR の URL でタグ付け → push
> 3. ECS サービスを再デプロイ → ECS が ECR から新しいイメージを取得
> 4. Cloud Map にタスクが登録され、API Gateway 経由でリクエスト受付開始

### 9-1. ECR にログイン

```bash
aws ecr get-login-password --region ap-northeast-1 | \
  docker login --username AWS --password-stdin \
  497614015161.dkr.ecr.ap-northeast-1.amazonaws.com
```

**確認:** `Login Succeeded` が表示される

### 9-2. Docker イメージのビルドと push

- [ ] ビルド、タグ付け、push

```bash
cd ~/Desktop/portfolio/receipt-ocr/backend

# ビルド（Tesseract OCR を含むため通常より時間がかかる）
docker build --platform linux/amd64 -t sunaba-receiptocr-backend .

# タグ付け
docker tag sunaba-receiptocr-backend:latest \
  497614015161.dkr.ecr.ap-northeast-1.amazonaws.com/sunaba-receiptocr-backend:latest

# push
docker push \
  497614015161.dkr.ecr.ap-northeast-1.amazonaws.com/sunaba-receiptocr-backend:latest
```

**確認:**

```bash
aws ecr list-images --repository-name sunaba-receiptocr-backend \
  --query 'imageIds[*].imageTag' --output table
# "latest" が表示される
```

### 9-3. ECS サービスを強制再デプロイ

```bash
aws ecs update-service --cluster sunaba-cluster \
  --service sunaba-receiptocr-backend --force-new-deployment
```

### 9-4. ECS タスクの起動確認

> Spring Boot + Tesseract のイメージは通常のアプリより重いため、起動に 2〜5 分かかることがあります。

- [ ] タスクが RUNNING になるまで待つ

```bash
aws ecs describe-services --cluster sunaba-cluster \
  --services sunaba-receiptocr-backend \
  --query 'services[0].{Running:runningCount,Desired:desiredCount,Status:status}' \
  --output table
# Running: 1, Desired: 1 になれば OK
```

- [ ] API Gateway 経由で API にアクセスできるか確認

```bash
API_GW=$(terraform output -raw api_gateway_endpoint)

curl -s $API_GW/api/receiptocr/actuator/health
# {"status":"UP"} が返る
```

**トラブルシューティング（タスクが起動しない場合）:**

```bash
# CloudWatch Logs でアプリのエラーログを確認
MSYS_NO_PATHCONV=1 aws logs tail /ecs/sunaba/receiptocr-backend --since 10m

# タスクの停止理由を確認
aws ecs describe-tasks --cluster sunaba-cluster \
  --tasks $(aws ecs list-tasks --cluster sunaba-cluster \
    --service sunaba-receiptocr-backend --desired-status STOPPED \
    --query 'taskArns[0]' --output text) \
  --query 'tasks[0].{Status:lastStatus,Reason:stoppedReason}' --output table
```

> **よくある原因:**
>
> - `OutOfMemory`: Tesseract が追加でメモリを消費するため、タスク定義のメモリを 1024MB 以上に設定
> - `Essential container exited`: DB 接続失敗、環境変数不足、Tesseract のインストール失敗
> - Tesseract 関連: `tessdata` パスが正しくないか、日本語データが不足

---

## Step 10: フロントエンドのデプロイ

> **目的:** React アプリを本番用にビルドし、S3 にアップロードして CloudFront 経由で配信します。

### 10-1. 本番用ビルド

```bash
cd ~/Desktop/portfolio/receipt-ocr/frontend

# 本番用環境変数を指定してビルド
MSYS_NO_PATHCONV=1 VITE_API_BASE_URL=/api/receiptocr \
  VITE_PORTAL_API_BASE_URL=/api/portal \
  npm run build
```

### 10-2. S3 にアップロード

```bash
BUCKET=$(cd ~/Desktop/portfolio/infrastructure/terraform && terraform output -raw frontend_bucket_name)
aws s3 sync dist/ s3://$BUCKET/receiptocr/ --delete
```

**確認:**

```bash
aws s3 ls s3://$BUCKET/receiptocr/ --recursive | head -5
# index.html, assets/ 等が表示される
```

### 10-3. CloudFront キャッシュ無効化

```bash
DIST_ID=$(cd ~/Desktop/portfolio/infrastructure/terraform && terraform output -raw cloudfront_distribution_id)
aws cloudfront create-invalidation --distribution-id $DIST_ID --paths "/receiptocr/*"
```

**確認:**

```bash
aws cloudfront list-invalidations --distribution-id $DIST_ID \
  --query 'InvalidationList.Items[0].Status' --output text
# Completed
```

---

## Step 11: Lambda のデプロイ

> **目的:** エクスポート CSV 生成用の Lambda 関数を SAM でデプロイします。
>
> **デプロイの流れ:**
>
> 1. SAM でビルド（Java 21 の Maven プロジェクトをパッケージング）
> 2. SAM でデプロイ（CloudFormation スタックとして AWS に作成）
> 3. SQS イベントソースマッピングにより、receipt-export-queue のメッセージで Lambda が自動起動

### 11-1. SAM ビルド

```bash
cd ~/Desktop/portfolio/receipt-ocr/lambda/export-csv
sam build
```

**確認:** `Build Succeeded` が表示される

### 11-2. SAM デプロイ

> 初回デプロイは `--guided` オプションで対話的に設定します。

```bash
sam deploy --guided
```

> **対話設定の入力例:**
>
> | 設定項目                         | 値                                     |
> | -------------------------------- | -------------------------------------- |
> | Stack Name                       | `receipt-ocr-export-lambda`            |
> | AWS Region                       | `ap-northeast-1`                       |
> | Confirm changes before deploy    | `Y`                                    |
> | Allow SAM CLI IAM role creation  | `N`（Terraform で作成済みのロールを使用） |
> | Save arguments to samconfig.toml | `Y`                                    |

### 11-3. Lambda の動作確認

- [ ] Lambda 関数が作成されたこと

```bash
aws lambda get-function --function-name ExportCsvFunction \
  --query 'Configuration.{Name:FunctionName,Runtime:Runtime,State:State}' --output table
# State: Active
```

- [ ] SQS イベントソースマッピングが設定されていること

```bash
aws lambda list-event-source-mappings --function-name ExportCsvFunction \
  --query 'EventSourceMappings[*].{Queue:EventSourceArn,State:State}' --output table
# receipt-export-queue の ARN が表示され、State: Enabled
```

---

## Step 12: 全体の動作確認

> **目的:** receipt-ocr の全コンポーネントが正しく連携して動くことを、実際のユーザー操作で確認します。
> フロントエンド → CloudFront → API Gateway → ECS → SQS → OCR ワーカー → RDS の全経路を検証します。

### 12-1. フロントエンドの表示確認

```bash
SITE_URL=$(cd ~/Desktop/portfolio/infrastructure/terraform && terraform output -raw site_url)
echo "Receipt OCR: $SITE_URL/receiptocr/"
```

- [ ] ブラウザで `https://sunaba-project.com/receiptocr/` にアクセス → 画面が表示される
  - → CloudFront → S3 の経路でフロントエンドが配信されている

### 12-2. 認証フローの確認

- [ ] Portal でログイン済みの状態で receipt-ocr にアクセス → JWT Cookie が共有されて認証済みになる
  - → 同一ドメイン（`sunaba-project.com`）なので HttpOnly Cookie が自動送信される

### 12-3. フロー①の動作確認（OCR）

> **確認する全経路:** フロントエンド → API Gateway → ECS → S3（画像保存）→ SQS → OCR ワーカー → Tesseract → DB → フロントエンド（ポーリング）

- [ ] 画面A でテスト画像（JPEG）をアップロード → バッチ作成成功（201 Created）
- [ ] 画面B に遷移 → ポーリングでステータスが更新される
- [ ] ジョブが COMPLETED に遷移 → OCR 結果が表示される
- [ ] 画像プレビューが署名付き URL で表示される
- [ ] OCR 結果を確認・編集 → 確定 → CONFIRMED に遷移 → レシートが作成される
- [ ] PDF ファイルのアップロード → PDF → 画像変換 → OCR が動作する

### 12-4. フロー②の動作確認（エクスポート）

> **確認する全経路:** フロントエンド → API Gateway → ECS → SQS → Lambda → DB → S3（CSV 保存）→ ECS（ダウンロード API）→ フロントエンド

- [ ] 画面C でレシート一覧が表示される
- [ ] フィルタ・ソート・ページングが動作する
- [ ] レシートの編集・削除が動作する
- [ ] レシートを選択 → CSV エクスポート → ポーリング → 自動ダウンロード

### 12-5. エラーケースの確認

- [ ] 不正なファイル形式（例: .txt）のアップロード → 400 エラー
- [ ] サイズ超過ファイルのアップロード → 400 エラー
- [ ] 未認証状態での API アクセス → 401 エラー
- [ ] SPA ルーティング: `/receiptocr/receipts` に直接アクセス → ページが表示される（404 にならない）

### 12-6. ログの確認

```bash
# ECS（バックエンド）のログ
MSYS_NO_PATHCONV=1 aws logs tail /ecs/sunaba/receiptocr-backend --since 30m

# Lambda のログ
MSYS_NO_PATHCONV=1 aws logs tail /aws/lambda/ExportCsvFunction --since 30m
```

- [ ] OCR 処理の開始・完了ログが出力されている
- [ ] エクスポート処理の開始・完了ログが出力されている
- [ ] エラーが発生していないこと

---

## Step 13: 運用スクリプトの更新

> **目的:** 既存の停止/起動スクリプトに receipt-ocr の ECS サービスを追加します。
> 停止時は receiptocr のサービスも合わせて停止し、起動時は合わせて起動するようにします。

### 13-1. stop-all.sh の更新

- [ ] `infrastructure/scripts/aws/stop-all.sh` に receiptocr サービスの停止を追加

```bash
# receipt-ocr のバックエンドを停止
aws ecs update-service --cluster sunaba-cluster \
  --service sunaba-receiptocr-backend --desired-count 0
```

### 13-2. start-all.sh の更新

- [ ] `infrastructure/scripts/aws/start-all.sh` に receiptocr サービスの起動を追加

```bash
# receipt-ocr のバックエンドを起動
aws ecs update-service --cluster sunaba-cluster \
  --service sunaba-receiptocr-backend --desired-count 1
```

### 13-3. 停止/起動の動作確認

- [ ] `stop-all.sh` を実行 → receiptocr を含む全サービスが停止すること

```bash
bash ~/Desktop/portfolio/infrastructure/scripts/aws/stop-all.sh
```

- [ ] `start-all.sh` を実行 → 全サービスが起動し、Step 12 の動作確認が再度成功すること

```bash
bash ~/Desktop/portfolio/infrastructure/scripts/aws/start-all.sh
```

---

## Step 14: CI/CD の構築

> **目的:** receipt-ocr のコードを GitHub に push するだけで自動的にテスト・デプロイが行われる
> CI/CD パイプラインを構築します。

### 14-1. GitHub OIDC ロールの更新

- [ ] `infrastructure/terraform/modules/github-oidc/` の対象リポジトリに receipt-ocr を追加
  - `github_repos` に `portfolio-receipt-ocr`（または実際のリポジトリ名）を追加

- [ ] `terraform plan` → `terraform apply`

### 14-2. CI ワークフロー（テスト）の作成

- [ ] `receipt-ocr/.github/workflows/ci.yml` を作成

```yaml
name: CI
on:
  pull_request:
    branches: [main]

jobs:
  backend-test:
    runs-on: ubuntu-latest
    if: contains(github.event.pull_request.changed_files, 'backend/')
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - run: cd backend && ./mvnw test

  frontend-lint-build:
    runs-on: ubuntu-latest
    if: contains(github.event.pull_request.changed_files, 'frontend/')
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - run: cd frontend && npm ci && npm run lint && npm run build

  lambda-build:
    runs-on: ubuntu-latest
    if: contains(github.event.pull_request.changed_files, 'lambda/')
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: aws-actions/setup-sam@v2
      - run: cd lambda/export-csv && sam build
```

### 14-3. CD ワークフロー（デプロイ）の作成

- [ ] `receipt-ocr/.github/workflows/deploy.yml` を作成
  - **変更検出**: `dorny/paths-filter` で backend / frontend / lambda の変更を判定
  - **バックエンド**: Docker ビルド → ECR push → ECS 再デプロイ
  - **フロントエンド**: npm build → S3 アップロード → CloudFront キャッシュ無効化
  - **Lambda**: SAM build → SAM deploy

```yaml
name: Deploy
on:
  push:
    branches: [main]

permissions:
  id-token: write
  contents: read

jobs:
  detect-changes:
    runs-on: ubuntu-latest
    outputs:
      backend: ${{ steps.filter.outputs.backend }}
      frontend: ${{ steps.filter.outputs.frontend }}
      lambda: ${{ steps.filter.outputs.lambda }}
    steps:
      - uses: actions/checkout@v4
      - uses: dorny/paths-filter@v3
        id: filter
        with:
          filters: |
            backend:
              - 'backend/**'
            frontend:
              - 'frontend/**'
            lambda:
              - 'lambda/**'

  deploy-backend:
    needs: detect-changes
    if: needs.detect-changes.outputs.backend == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::497614015161:role/sunaba-github-actions-deploy
          aws-region: ap-northeast-1
      - uses: aws-actions/amazon-ecr-login@v2
      - run: |
          cd backend
          docker build --platform linux/amd64 -t sunaba-receiptocr-backend .
          docker tag sunaba-receiptocr-backend:latest \
            497614015161.dkr.ecr.ap-northeast-1.amazonaws.com/sunaba-receiptocr-backend:latest
          docker push \
            497614015161.dkr.ecr.ap-northeast-1.amazonaws.com/sunaba-receiptocr-backend:latest
      - run: |
          aws ecs update-service --cluster sunaba-cluster \
            --service sunaba-receiptocr-backend --force-new-deployment

  deploy-frontend:
    needs: detect-changes
    if: needs.detect-changes.outputs.frontend == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::497614015161:role/sunaba-github-actions-deploy
          aws-region: ap-northeast-1
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - run: |
          cd frontend
          npm ci
          VITE_API_BASE_URL=/api/receiptocr \
            VITE_PORTAL_API_BASE_URL=/api/portal \
            npm run build
      - run: |
          BUCKET=$(aws cloudformation describe-stacks --query "..." --output text)
          aws s3 sync frontend/dist/ s3://$BUCKET/receiptocr/ --delete
          aws cloudfront create-invalidation \
            --distribution-id <DISTRIBUTION_ID> --paths "/receiptocr/*"

  deploy-lambda:
    needs: detect-changes
    if: needs.detect-changes.outputs.lambda == 'true'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::497614015161:role/sunaba-github-actions-deploy
          aws-region: ap-northeast-1
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - uses: aws-actions/setup-sam@v2
      - run: |
          cd lambda/export-csv
          sam build
          sam deploy --no-confirm-changeset
```

### 14-4. 動作確認

- [ ] PR を作成して CI が動くことを確認（テスト・ビルドが緑になる）
- [ ] main にマージして CD が動くことを確認（デプロイが成功する）
- [ ] ブラウザで変更が反映されていることを確認

---

## コスト見積もり

> receipt-ocr 追加による月額コスト増分（24時間稼働の場合）

| リソース                            | 月額見積もり | 備考                                           |
| ----------------------------------- | ------------ | ---------------------------------------------- |
| ECS Fargate（receiptocr-backend）   | ~$15         | 0.25 vCPU / 1 GB（Tesseract 用にメモリ増量）  |
| SQS × 4 キュー                     | ~$0          | 無料枠内（100万リクエスト/月）                  |
| S3（receipt-uploads）               | ~$0.5        | 低トラフィック想定                              |
| Lambda（export-csv）                | ~$0          | 無料枠内（100万リクエスト/月）                  |
| CloudWatch Logs                     | ~$0.5        | ログ量に依存                                    |
| **合計**                            | **~$16**     |                                                 |

> **停止時:** ECS タスクを停止すれば Fargate 課金が止まり、SQS / S3 / Lambda はリクエスト課金のため ~$0 になります。

---

## 設計判断メモ

### 1. Lambda の VPC 接続

| 選択肢                   | コスト  | 制約                                      |
| ------------------------ | ------- | ----------------------------------------- |
| VPC Lambda + NAT Gateway | ~$32/月 | NAT Gateway のコストが高い                |
| VPC Lambda + VPC エンドポイント | ~$7/月 | S3, SQS, SSM 用に3つのエンドポイント必要 |
| RDS Proxy 経由           | ~$15/月 | コネクション管理が改善される              |
| **RDS のパブリックアクセス（非推奨）** | $0 | セキュリティ上のリスクあり        |

→ **ポートフォリオ用途では VPC エンドポイント方式を推奨**

### 2. ECS タスクのメモリサイズ

| サイズ           | Tesseract 対応 | コスト    |
| ---------------- | -------------- | --------- |
| 512 MB（既存）   | 不足の可能性   | ~$7.5/月  |
| **1024 MB（推奨）** | 十分        | ~$15/月   |
| 2048 MB          | 余裕あり       | ~$30/月   |

→ **1024 MB を推奨。OCR 処理は CPU + メモリを多く消費するため、512 MB では不安定になる可能性がある**

### 3. Tesseract のベースイメージ

| ベースイメージ          | サイズ    | Tesseract 対応          |
| ----------------------- | --------- | ----------------------- |
| `eclipse-temurin:21-jre-alpine` | ~200 MB | `apk add tesseract-ocr` |
| `eclipse-temurin:21-jre`（Debian） | ~300 MB | `apt install tesseract-ocr tesseract-ocr-jpn` |

→ **Alpine で動作確認し、問題があれば Debian に切り替える**

---

## 参考ドキュメント

- `@infrastructure/docs/06_deployment/DEPLOY_TASKS.md` — SUNABA 本体のデプロイタスクリスト
- `@infrastructure/docs/06_deployment/AWS_DEPLOY_GUIDE.md` — AWS デプロイガイド
- `@infrastructure/terraform/` — Terraform モジュール一式
- `@docs/1.要件定義/要件定義書.md` — 要件定義書
- `@docs/2.基本設計/非同期処理フロー設計書.md` — SQS / Lambda の設計
- `@docs/2.基本設計/API設計書.md` — API エンドポイント一覧
- `@docs/3.詳細設計/バックエンドクラス設計書.md` — Lambda プロジェクト構成
- `@docs/backend/TASKS.md` — バックエンド実装タスクリスト
- `@docs/frontend/TASKS.md` — フロントエンド実装タスクリスト

---

**作成日**: 2026-03-08
**最終更新**: 2026-03-08
