-- ============================================================
-- 領収書OCR スキーマ初期化
-- ============================================================

-- ------------------------------------------------------------
-- updated_at 自動更新トリガー関数
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION receipt_ocr.update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- 1. upload_batches（アップロードバッチ）
-- ============================================================
CREATE TABLE receipt_ocr.upload_batches (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     VARCHAR(64) NOT NULL,
    total_files INT         NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_upload_batches PRIMARY KEY (id)
);

CREATE INDEX idx_upload_batches_user_id
    ON receipt_ocr.upload_batches (user_id);

-- ============================================================
-- 2. ocr_jobs（OCR ジョブ）
-- ============================================================
CREATE TABLE receipt_ocr.ocr_jobs (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    batch_id            UUID          NOT NULL,
    user_id             VARCHAR(64)   NOT NULL,
    file_name           VARCHAR(255)  NOT NULL,
    file_size           BIGINT        NOT NULL,
    mime_type           VARCHAR(50)   NOT NULL,
    storage_key         VARCHAR(512)  NOT NULL,
    converted_image_key VARCHAR(512),
    status              VARCHAR(20)   NOT NULL,
    retry_count         INT           NOT NULL DEFAULT 0,
    error_message       VARCHAR(1000),
    ocr_raw_text        TEXT,
    ocr_date            DATE,
    ocr_amount          INT,
    ocr_store_name      VARCHAR(200),
    ocr_description     TEXT,
    ocr_tax_category    VARCHAR(20),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,

    CONSTRAINT pk_ocr_jobs PRIMARY KEY (id),
    CONSTRAINT fk_ocr_jobs_batch
        FOREIGN KEY (batch_id) REFERENCES receipt_ocr.upload_batches (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_ocr_jobs_status
        CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'CONFIRMED', 'FAILED')),
    CONSTRAINT chk_ocr_jobs_retry_count
        CHECK (retry_count >= 0 AND retry_count <= 3)
);

CREATE INDEX idx_ocr_jobs_batch_id
    ON receipt_ocr.ocr_jobs (batch_id);

CREATE INDEX idx_ocr_jobs_user_status
    ON receipt_ocr.ocr_jobs (user_id, status);

CREATE TRIGGER trg_ocr_jobs_updated_at
    BEFORE UPDATE ON receipt_ocr.ocr_jobs
    FOR EACH ROW
    EXECUTE FUNCTION receipt_ocr.update_updated_at();

-- ============================================================
-- 3. receipts（確定済みレシート）
-- ============================================================
CREATE TABLE receipt_ocr.receipts (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    job_id       UUID         NOT NULL,
    user_id      VARCHAR(64)  NOT NULL,
    receipt_date DATE         NOT NULL,
    amount       INT          NOT NULL,
    store_name   VARCHAR(200) NOT NULL,
    description  TEXT,
    tax_category VARCHAR(20),
    confirmed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_receipts PRIMARY KEY (id),
    CONSTRAINT uq_receipts_job_id UNIQUE (job_id),
    CONSTRAINT fk_receipts_job
        FOREIGN KEY (job_id) REFERENCES receipt_ocr.ocr_jobs (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_receipts_amount
        CHECK (amount >= 1)
);

CREATE INDEX idx_receipts_user_date
    ON receipt_ocr.receipts (user_id, receipt_date DESC);

CREATE INDEX idx_receipts_user_store
    ON receipt_ocr.receipts (user_id, store_name);

CREATE INDEX idx_receipts_user_amount
    ON receipt_ocr.receipts (user_id, amount);

CREATE TRIGGER trg_receipts_updated_at
    BEFORE UPDATE ON receipt_ocr.receipts
    FOR EACH ROW
    EXECUTE FUNCTION receipt_ocr.update_updated_at();

-- ============================================================
-- 4. export_jobs（エクスポートジョブ）
-- ============================================================
CREATE TABLE receipt_ocr.export_jobs (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    user_id        VARCHAR(64)   NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    total_receipts INT           NOT NULL,
    file_name      VARCHAR(255),
    storage_key    VARCHAR(512),
    error_message  VARCHAR(1000),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMPTZ,

    CONSTRAINT pk_export_jobs PRIMARY KEY (id),
    CONSTRAINT chk_export_jobs_status
        CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_export_jobs_user_id
    ON receipt_ocr.export_jobs (user_id);

-- ============================================================
-- 5. export_job_receipts（エクスポート対象レシート中間テーブル）
-- ============================================================
CREATE TABLE receipt_ocr.export_job_receipts (
    export_job_id UUID NOT NULL,
    receipt_id    UUID NOT NULL,

    CONSTRAINT pk_export_job_receipts PRIMARY KEY (export_job_id, receipt_id),
    CONSTRAINT fk_ejr_export_job
        FOREIGN KEY (export_job_id) REFERENCES receipt_ocr.export_jobs (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_ejr_receipt
        FOREIGN KEY (receipt_id) REFERENCES receipt_ocr.receipts (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_export_job_receipts_receipt
    ON receipt_ocr.export_job_receipts (receipt_id);
