/* 
   --- A Note on Table Partitions ---

   All tables are partitioned by shopper_id -- I think this makes
   sense because we want to keep all activity associated with a single
   user in the same partition, even if that means activity across a
   single site is spread across partitions. Very rarely will we be
   querying for aggregate activity for an entire site, but nearly
   every single query we make will be by shopper_id. Also,
   partitioning by shopper_id should spread data as evenly as possible
   across partitions, whereas if we were partitioning by site_id, data
   would likely be spread unevenly based on the difference in activity
   volume between sites.
*/

/* site_visits contains 1 row for every site visit event */

CREATE TABLE site_visits
(
  message_id     varchar(36)    NOT NULL,
  site_id        varchar(36)    NOT NULL,
  shopper_id     varchar(36)    NOT NULL, 
  created_at     timestamp      DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT PK_site_visits PRIMARY KEY
  (
    site_id, shopper_id, message_id
  )
);

CREATE INDEX SiteVisitsSiteIdShopperIdIdx ON site_visits (site_id, shopper_id);
CREATE ASSUMEUNIQUE INDEX SiteVisitsMessageIdIdx ON site_visits (message_id);
PARTITION TABLE site_visits ON COLUMN shopper_id;

/* product_views contains 1 row for every product view event */

CREATE TABLE product_views
(
  message_id     varchar(36)    NOT NULL,
  site_id        varchar(36)    NOT NULL,
  shopper_id     varchar(36)    NOT NULL,
  product_id     varchar(255)   NOT NULL,
  category_id    varchar(255)   DEFAULT NULL,
  created_at     timestamp      DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT PK_product_views PRIMARY KEY
  (
    site_id, shopper_id, message_id
  )
);

CREATE ASSUMEUNIQUE INDEX ProductViewsMessageIdIdx ON product_views (message_id);
CREATE INDEX ProductViewsSiteIdShopperIdIdx ON product_views (site_id, shopper_id);
PARTITION TABLE product_views ON COLUMN shopper_id;
/********************************************************************************/

/* product_adds contains 1 row for every product add event, meaning a
   user has added a product to their shopping cart
*/

CREATE TABLE product_adds
(
  message_id            varchar(36)     NOT NULL,
  site_id               varchar(36)     NOT NULL,
  shopper_id            varchar(36)     NOT NULL,
  session_id            varchar(36)     NOT NULL,
  product_id            varchar(255)    NOT NULL,
  category_id           varchar(255)    DEFAULT NULL,
  quantity              integer         NOT NULL,
  created_at            timestamp       DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT PK_product_adds PRIMARY KEY
  (
    site_id, shopper_id, message_id
  )
);

CREATE ASSUMEUNIQUE INDEX ProductAddsMessageIdIdx ON product_adds (message_id);
CREATE INDEX ProductAddsSiteIdShopperIdIdx ON product_adds (site_id, shopper_id);
PARTITION TABLE product_adds ON COLUMN shopper_id;
/********************************************************************************/

/* orders contains 1 row for every order placed on a site */

CREATE TABLE orders
(
  message_id            varchar(36)     NOT NULL,
  order_number          varchar(255)    DEFAULT NULL,
  site_id               varchar(36)     NOT NULL,
  shopper_id            varchar(36)     NOT NULL,
  currency              varchar(3)      DEFAULT 'USD',
  total_amount_pre_tax  decimal         NOT NULL,
  total_amount_with_tax decimal         NOT NULL,
  created_at            timestamp       DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT PK_orders PRIMARY KEY
  (
    site_id, shopper_id, message_id
  ) 
);

CREATE ASSUMEUNIQUE INDEX OrdersMessageIdIdx ON orders (message_id);
CREATE INDEX OrdersSiteIdShopperIdx ON orders (site_id, shopper_id);
PARTITION TABLE orders ON COLUMN shopper_id;
/********************************************************************************/

/* order_products contains 1 row for every product for every order. 1
   row in the orders table will contain 1 to many in this table 
*/

CREATE TABLE order_products
(
  message_id             varchar(36)     NOT NULL,
  site_id                varchar(36)     NOT NULL,
  shopper_id             varchar(36)     NOT NULL,
  product_id             varchar(255)    NOT NULL,
  quantity               integer         NOT NULL,
  total_line_amount      decimal         NOT NULL,
  CONSTRAINT PK_order_products PRIMARY KEY
  (
    site_id, shopper_id, message_id, product_id
  )
);

PARTITION TABLE order_products ON COLUMN shopper_id;
/********************************************************************************/

/* promo_uses contains 1 row for every promo use event, meaning a
   shopper has used a promotably promo on a shopping website, and
   completed their purchase.
*/

CREATE TABLE promo_uses
(
  message_id            varchar(36)     NOT NULL,
  site_id               varchar(36)     NOT NULL,
  shopper_id            varchar(36)     NOT NULL,
  promo_id              varchar(36)     NOT NULL,
  discount_amount       decimal         NOT NULL,
  currency              varchar(3)      DEFAULT 'USD',
  created_at            timestamp       DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT PK_promo_uses PRIMARY KEY
  (
    site_id, shopper_id, message_id, promo_id
  )
);

CREATE INDEX PromoUsesSiteIdShopperIdPromoIdIdx ON promo_uses (site_id, shopper_id, promo_id);
PARTITION TABLE promo_uses ON COLUMN shopper_id;
