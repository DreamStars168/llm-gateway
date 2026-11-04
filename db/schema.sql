-- =====================================================================
-- LLM Gateway 初始化脚本 (MySQL 8.0+)
-- 字符集 utf8mb4 / 引擎 InnoDB / 时间精度 DATETIME(3)
-- 金额统一 micro-USD (BIGINT)，1 USD = 1,000,000
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 租户与用户
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  name            VARCHAR(64)   NOT NULL,
  code            VARCHAR(32)   NOT NULL COMMENT '对外唯一标识',
  balance_micro   BIGINT        NOT NULL DEFAULT 0 COMMENT '余额(micro-USD)，可为负=欠款',
  daily_quota_micro BIGINT      NULL COMMENT '日额度上限，NULL=不限',
  quota_mode      VARCHAR(16)   NOT NULL DEFAULT 'FAIL_OPEN' COMMENT 'FAIL_OPEN/FAIL_CLOSED/LOCAL_QUOTA',
  status          TINYINT       NOT NULL DEFAULT 1,
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户';

CREATE TABLE IF NOT EXISTS sys_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id     BIGINT       NULL COMMENT 'NULL=平台超管',
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          VARCHAR(24)  NOT NULL DEFAULT 'VIEWER' COMMENT 'SUPER_ADMIN/ADMIN/OPERATOR/VIEWER',
  status        TINYINT      NOT NULL DEFAULT 1,
  last_login_at DATETIME(3)  NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理端用户';

-- ---------------------------------------------------------------------
-- 分组 / 渠道 / 凭证
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_group (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL,
  name        VARCHAR(64)  NOT NULL,
  discount    DECIMAL(6,4) NOT NULL DEFAULT 1.0000 COMMENT '分组折扣',
  description VARCHAR(255) NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限与计费分组';

CREATE TABLE IF NOT EXISTS channel (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(64)   NOT NULL,
  provider      VARCHAR(32)   NOT NULL COMMENT 'openai/anthropic/gemini/deepseek/glm/custom',
  protocol      VARCHAR(24)   NOT NULL COMMENT 'OPENAI_CHAT/OPENAI_RESPONSES/ANTHROPIC/GEMINI',
  base_url      VARCHAR(255)  NOT NULL,
  group_id      BIGINT        NOT NULL,
  priority      INT           NOT NULL DEFAULT 100 COMMENT '越小越优先（硬分层）',
  weight        INT           NOT NULL DEFAULT 100 COMMENT '层内权重',
  max_inflight  INT           NOT NULL DEFAULT 200,
  timeout_ms    INT           NOT NULL DEFAULT 60000,
  passthrough   TINYINT(1)    NOT NULL DEFAULT 1,
  allow_private_net TINYINT(1) NOT NULL DEFAULT 0 COMMENT '允许内网地址(自建vLLM)',
  status        TINYINT       NOT NULL DEFAULT 1,
  extra         JSON          NULL COMMENT '头模板/代理/限速等',
  created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_channel_name (name),
  KEY idx_group_status (group_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上游渠道';

CREATE TABLE IF NOT EXISTS channel_key (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  channel_id     BIGINT       NOT NULL,
  label          VARCHAR(64)  NULL,
  cipher         VARBINARY(512) NOT NULL COMMENT 'AES-256-GCM 密文',
  key_version    INT          NOT NULL DEFAULT 1,
  weight         INT          NOT NULL DEFAULT 100,
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 0禁用 2失效',
  cooldown_until DATETIME(3)  NULL,
  last_error     VARCHAR(255) NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_channel_status (channel_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道凭证(多账号轮换)';

-- ---------------------------------------------------------------------
-- 虚拟模型 / 别名 / 路由
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS virtual_model (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  name           VARCHAR(64)  NOT NULL COMMENT '对客户端暴露的模型名',
  display_name   VARCHAR(128) NULL,
  context_window INT          NULL,
  capabilities   JSON         NULL COMMENT '["text","vision","tools","thinking"]',
  status         TINYINT      NOT NULL DEFAULT 1,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_vm_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟模型';

CREATE TABLE IF NOT EXISTS model_alias (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  virtual_model_id BIGINT       NOT NULL,
  alias            VARCHAR(128) NOT NULL,
  match_type       VARCHAR(12)  NOT NULL DEFAULT 'EXACT' COMMENT 'EXACT/PREFIX/REGEX',
  priority         INT          NOT NULL DEFAULT 100,
  UNIQUE KEY uk_alias (alias, match_type),
  KEY idx_vm (virtual_model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型别名归一';

CREATE TABLE IF NOT EXISTS model_route (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  virtual_model_id BIGINT        NOT NULL,
  channel_id       BIGINT        NOT NULL,
  upstream_model   VARCHAR(128)  NOT NULL,
  priority         INT           NOT NULL DEFAULT 100,
  weight           INT           NOT NULL DEFAULT 100,
  price_ratio      DECIMAL(10,4) NOT NULL DEFAULT 1.0000,
  context_window   INT           NULL,
  enabled          TINYINT(1)    NOT NULL DEFAULT 1,
  UNIQUE KEY uk_vm_channel_model (virtual_model_id, channel_id, upstream_model),
  KEY idx_vm_enabled (virtual_model_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟模型到真实模型的路由';

CREATE TABLE IF NOT EXISTS route_policy (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_type      VARCHAR(16) NOT NULL COMMENT 'GLOBAL/GROUP/VIRTUAL_MODEL',
  scope_id        BIGINT      NULL,
  strategy        VARCHAR(16) NOT NULL DEFAULT 'STICKY' COMMENT 'PRIORITY/WEIGHTED/STICKY/LEAST_LATENCY',
  sticky_ttl_sec  INT         NOT NULL DEFAULT 1800,
  retry_max       INT         NOT NULL DEFAULT 2,
  failover_on     JSON        NULL,
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scope (scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由策略';

-- ---------------------------------------------------------------------
-- API Key
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_key (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id      BIGINT       NOT NULL,
  name           VARCHAR(64)  NOT NULL,
  key_prefix     CHAR(16)     NOT NULL COMMENT 'sk-lg-xxxx，用于索引',
  key_hash       CHAR(64)     NOT NULL COMMENT 'sha256(secret)',
  group_id       BIGINT       NOT NULL,
  allowed_models JSON         NULL COMMENT 'NULL=全部',
  rpm_limit      INT          NULL,
  tpm_limit      INT          NULL,
  daily_quota_micro BIGINT    NULL,
  status         TINYINT      NOT NULL DEFAULT 1,
  expires_at     DATETIME     NULL,
  rotate_grace_until DATETIME NULL COMMENT '轮换后旧key双活截止',
  last_used_at   DATETIME(3)  NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_prefix (key_prefix),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户端API Key(只存哈希)';

-- ---------------------------------------------------------------------
-- 定价
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS price_rule (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  virtual_model   VARCHAR(64)   NOT NULL,
  currency        CHAR(3)       NOT NULL DEFAULT 'USD',
  tier_json       JSON          NOT NULL COMMENT '[{when:{input_tokens_lte:200000},input:3.0,output:15.0,...}]',
  channel_multiplier JSON       NULL COMMENT '{channelId: multiplier}',
  effective_from  DATETIME      NOT NULL,
  effective_to    DATETIME      NULL,
  status          TINYINT       NOT NULL DEFAULT 1,
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_vm_time (virtual_model, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分级定价（每百万token单价）';

-- ---------------------------------------------------------------------
-- 配额预扣流水
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quota_reservation (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id   VARCHAR(40) NOT NULL,
  tenant_id    BIGINT      NOT NULL,
  api_key_id   BIGINT      NOT NULL,
  amount_micro BIGINT      NOT NULL COMMENT '预扣额',
  actual_micro BIGINT      NULL COMMENT '结算额',
  status       VARCHAR(12) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED/SETTLED/RELEASED',
  degraded     TINYINT(1)  NOT NULL DEFAULT 0,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  settled_at   DATETIME(3) NULL,
  UNIQUE KEY uk_request (request_id),
  KEY idx_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预扣流水，保留7天';

-- ---------------------------------------------------------------------
-- 请求日志与尝试
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS relay_log (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id       VARCHAR(40)  NOT NULL,
  tenant_id        BIGINT       NOT NULL,
  api_key_id       BIGINT       NOT NULL,
  virtual_model    VARCHAR(64)  NOT NULL,
  upstream_model   VARCHAR(128) NULL,
  channel_id       BIGINT       NULL,
  channel_name     VARCHAR(64)  NULL,
  protocol_in      VARCHAR(24)  NOT NULL,
  protocol_out     VARCHAR(24)  NOT NULL,
  gateway_stream   TINYINT(1)   NOT NULL,
  status_code      SMALLINT     NOT NULL,
  error_code       VARCHAR(48)  NULL,
  input_tokens     INT          NOT NULL DEFAULT 0,
  output_tokens    INT          NOT NULL DEFAULT 0,
  cached_tokens    INT          NOT NULL DEFAULT 0,
  reasoning_tokens INT          NOT NULL DEFAULT 0,
  usage_source     VARCHAR(12)  NOT NULL DEFAULT 'UPSTREAM' COMMENT 'UPSTREAM/ESTIMATED',
  cost_micro       BIGINT       NOT NULL DEFAULT 0,
  ttft_ms          INT          NULL,
  total_ms         INT          NULL,
  retry_count      TINYINT      NOT NULL DEFAULT 0,
  sticky_hit       TINYINT(1)   NOT NULL DEFAULT 0,
  route_trace      JSON         NULL,
  created_at       DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_request (request_id),
  KEY idx_tenant_time (tenant_id, created_at),
  KEY idx_model_time (virtual_model, created_at),
  KEY idx_channel_time (channel_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='请求明细（建议按月分区）';

CREATE TABLE IF NOT EXISTS relay_attempt (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id   VARCHAR(40) NOT NULL,
  seq          TINYINT     NOT NULL COMMENT '第几次尝试',
  channel_id   BIGINT      NOT NULL,
  channel_key_id BIGINT    NULL,
  status_code  SMALLINT    NULL,
  error_code   VARCHAR(48) NULL,
  elapsed_ms   INT         NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_request (request_id),
  KEY idx_channel_time (channel_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每次转发尝试';

-- ---------------------------------------------------------------------
-- 聚合统计
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS usage_daily (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  stat_date      DATE         NOT NULL,
  tenant_id      BIGINT       NOT NULL,
  virtual_model  VARCHAR(64)  NOT NULL,
  channel_id     BIGINT       NOT NULL DEFAULT 0,
  request_count  BIGINT       NOT NULL DEFAULT 0,
  error_count    BIGINT       NOT NULL DEFAULT 0,
  input_tokens   BIGINT       NOT NULL DEFAULT 0,
  output_tokens  BIGINT       NOT NULL DEFAULT 0,
  cached_tokens  BIGINT       NOT NULL DEFAULT 0,
  cost_micro     BIGINT       NOT NULL DEFAULT 0,
  ttft_sum_ms    BIGINT       NOT NULL DEFAULT 0,
  total_sum_ms   BIGINT       NOT NULL DEFAULT 0,
  UNIQUE KEY uk_dim (stat_date, tenant_id, virtual_model, channel_id),
  KEY idx_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日聚合（幂等UPSERT）';

-- ---------------------------------------------------------------------
-- 账单
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bill_item (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  bill_no       VARCHAR(40) NOT NULL,
  tenant_id     BIGINT      NOT NULL,
  request_id    VARCHAR(40) NOT NULL,
  virtual_model VARCHAR(64) NOT NULL,
  channel_id    BIGINT      NULL,
  amount_micro  BIGINT      NOT NULL,
  usage_source  VARCHAR(12) NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_request (request_id),
  KEY idx_tenant_time (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单明细';

-- ---------------------------------------------------------------------
-- Playground / Prompt 版本
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS prompt_version (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id  BIGINT       NOT NULL,
  name       VARCHAR(128) NULL,
  model      VARCHAR(64)  NOT NULL,
  params     JSON         NOT NULL,
  messages   JSON         NOT NULL,
  tags       JSON         NULL,
  parent_id  BIGINT       NULL COMMENT '版本链',
  created_by BIGINT       NOT NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_tenant_time (tenant_id, created_at),
  KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Playground Prompt归档';

-- ---------------------------------------------------------------------
-- 审计 / MQ兜底 / 配置版本
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id   BIGINT       NULL,
  user_id     BIGINT       NOT NULL,
  action      VARCHAR(64)  NOT NULL,
  resource    VARCHAR(64)  NOT NULL,
  resource_id VARCHAR(64)  NULL,
  before_json JSON         NULL,
  after_json  JSON         NULL,
  ip          VARCHAR(45)  NULL,
  user_agent  VARCHAR(255) NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_time (user_id, created_at),
  KEY idx_resource (resource, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理操作审计';

CREATE TABLE IF NOT EXISTS usage_outbox (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  payload      JSON         NOT NULL,
  status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0待发 1已发 2失败',
  retry_count  INT          NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ发布失败兜底';

CREATE TABLE IF NOT EXISTS config_version (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  version    BIGINT      NOT NULL,
  snapshot   JSON        NULL COMMENT '路由表快照（保留最近20版）',
  changed_by BIGINT      NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置版本与回滚快照';

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 初始化数据（演示用，生产请修改）
-- =====================================================================
INSERT INTO tenant (name, code, balance_micro, quota_mode)
VALUES ('默认租户', 'default', 100000000, 'FAIL_OPEN')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO api_group (tenant_id, name, discount)
SELECT t.id, '默认分组', 1.0000 FROM tenant t WHERE t.code = 'default'
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO virtual_model (name, display_name, context_window, capabilities)
VALUES
 ('claude-sonnet-4', 'Claude Sonnet 4', 200000, '["text","vision","tools","thinking"]'),
 ('gpt-4o',          'GPT-4o',          128000, '["text","vision","tools"]'),
 ('deepseek-chat',   'DeepSeek Chat',    64000,  '["text","tools"]')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

INSERT INTO model_alias (virtual_model_id, alias, match_type, priority)
SELECT vm.id, 'claude-sonnet-4-20250514', 'EXACT', 10 FROM virtual_model vm WHERE vm.name='claude-sonnet-4'
ON DUPLICATE KEY UPDATE alias = VALUES(alias);

INSERT INTO route_policy (scope_type, scope_id, strategy, sticky_ttl_sec, retry_max)
VALUES ('GLOBAL', NULL, 'STICKY', 1800, 2)
ON DUPLICATE KEY UPDATE strategy = VALUES(strategy);

