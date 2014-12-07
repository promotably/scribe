/* 
   --- A Note on Table Partitions ---

   All tables are partitioned by visitor id -- I think this makes
   sense because we want to keep all activity associated with a single
   user in the same partition, even if that means activity across a
   single site is spread across partitions. Very rarely will we be
   querying for aggregate activity for an entire site, but nearly
   every single query we make will be by visitor id. Also,
   partitioning by visitor id should spread data as evenly as possible
   across partitions, whereas if we were partitioning by site_id, data
   would likely be spread unevenly based on the difference in activity
   volume between sites.
*/

CREATE TABLE site_visits
(
  message_id     varchar(36)    NOT NULL,
  site_id        varchar(36)    NOT NULL,
  visitor_id     varchar(36)    NOT NULL, 
  created_at     timestamp      DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT PK_site_visits PRIMARY KEY
  (
    site_id, visitor_id, message_id
  )
);

CREATE INDEX SiteVisitsSiteIdVisitorIdIdx ON site_visits (site_id, visitor_id);
CREATE ASSUMEUNIQUE INDEX SiteVisitsMessageIdIdx ON site_visits (message_id);
PARTITION TABLE site_visits ON COLUMN visitor_id;

/* product_views contains 1 row for every product view event */

CREATE TABLE product_views
(
  message_id     varchar(36)    NOT NULL,
  site_id        varchar(36)    NOT NULL,
  visitor_id     varchar(36)    NOT NULL,
  product_id     varchar(255)   NOT NULL,
  category_id    varchar(255)   DEFAULT NULL,
  created_at     timestamp      DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT PK_product_views PRIMARY KEY
  (
    site_id, visitor_id, message_id
  )
);

CREATE ASSUMEUNIQUE INDEX ProductViewsMessageIdIdx ON product_views (message_id);
CREATE INDEX ProductViewsSiteIdVisitorIdIdx ON product_views (site_id, visitor_id);
PARTITION TABLE product_views ON COLUMN visitor_id;

CREATE TABLE orders
(
  message_id            varchar(36)     NOT NULL,
  order_number          varchar(255)    DEFAULT NULL,
  site_id               varchar(36)     NOT NULL,
  visitor_id            varchar(36)     NOT NULL,
  currency              varchar(3)      DEFAULT 'USD',
  total_amount_pre_tax  decimal         NOT NULL,
  total_amount_with_tax decimal         NOT NULL,
  created_at            timestamp       DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT PK_orders PRIMARY KEY
  (
    site_id, visitor_id, message_id
  ) 
);

CREATE ASSUMEUNIQUE INDEX OrdersMessageIdIdx ON orders (message_id);
CREATE INDEX OrdersSiteIdVisitorIdx ON orders (site_id, visitor_id);
PARTITION TABLE orders ON COLUMN visitor_id;

CREATE TABLE order_products
(
 message_id             varchar(36)     NOT NULL,
 site_id                varchar(36)     NOT NULL,
 visitor_id             varchar(36)     NOT NULL,
 product_id             varchar(255)    NOT NULL,
 quantity               integer         NOT NULL,
 total_line_amount      decimal         NOT NULL,
 CONSTRAINT PK_order_products PRIMARY KEY
 (
   site_id, visitor_id, message_id, product_id
 )
);

PARTITION TABLE order_products ON COLUMN visitor_id;
