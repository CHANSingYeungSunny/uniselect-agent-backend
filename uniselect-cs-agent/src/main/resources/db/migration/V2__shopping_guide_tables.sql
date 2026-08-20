-- =============================================================
-- UniSelect 导购 Agent 数据表迁移（V2）
-- -------------------------------------------------------------
-- 说明：本期工程使用 Mock 内存实现（profile=mock），运行期不连库。
-- 本脚本作为后续无缝切换真实 pgvector / Redis / PostgreSQL 的 DDL 依据。
-- 向量列依赖 pgvector 扩展（CREATE EXTENSION IF NOT EXISTS vector;）。
-- =============================================================

-- 商品目录（静态层 + 动态层字段合并）
CREATE TABLE IF NOT EXISTS product_catalog (
    sku_id          VARCHAR(64)     NOT NULL,
    merchant_id     VARCHAR(64)     NOT NULL,
    name            VARCHAR(256)    NOT NULL,
    -- 类目路径：形如 "家居/厨房/杯具"，最后一段为子品类
    category_path   VARCHAR(512)    NOT NULL,
    price           NUMERIC(12, 2)  NOT NULL,
    cost            NUMERIC(12, 2)  NOT NULL,
    inventory       INT             NOT NULL DEFAULT 0,
    -- 上下架状态：1=上架，0=下架
    status          SMALLINT        NOT NULL DEFAULT 1,
    -- 是否参加活动
    is_promotion    BOOLEAN         NOT NULL DEFAULT FALSE,
    promotion_discount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    promotion_start TIMESTAMP,
    promotion_end   TIMESTAMP,
    created_at      TIMESTAMP       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, sku_id)
);

CREATE INDEX IF NOT EXISTS idx_product_merchant_status
    ON product_catalog (merchant_id, status);
CREATE INDEX IF NOT EXISTS idx_product_category
    ON product_catalog (merchant_id, category_path);

-- 商品向量表（pgvector）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS product_embeddings (
    sku_id      VARCHAR(64)     NOT NULL,
    merchant_id VARCHAR(64)     NOT NULL,
    -- 向量维度按真实 embedding 模型确定（此处 1536 为占位）
    embedding   VECTOR(1536),
    model       VARCHAR(64)     NOT NULL,
    updated_at  TIMESTAMP       NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, sku_id),
    FOREIGN KEY (merchant_id, sku_id)
        REFERENCES product_catalog (merchant_id, sku_id)
);

-- 向量近似检索索引（ivfflat，可按数据量换 hnsw）
CREATE INDEX IF NOT EXISTS idx_product_embedding
    ON product_embeddings USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- 推荐事件埋点（曝光/点击/加购/下单）
CREATE TABLE IF NOT EXISTS recommendation_events (
    id          BIGSERIAL       PRIMARY KEY,
    event_id    VARCHAR(64)     NOT NULL,
    merchant_id VARCHAR(64)     NOT NULL,
    session_id  VARCHAR(64)     NOT NULL,
    sku_id      VARCHAR(64)     NOT NULL,
    -- impression / click / add_cart / order
    event_type  VARCHAR(32)     NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT now(),
    -- 幂等唯一约束：同一 event_id 仅记录一次
    UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_rec_event_merchant_session
    ON recommendation_events (merchant_id, session_id);
CREATE INDEX IF NOT EXISTS idx_rec_event_sku
    ON recommendation_events (merchant_id, sku_id, event_type);
