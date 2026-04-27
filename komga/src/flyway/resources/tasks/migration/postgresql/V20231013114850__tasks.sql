-- Current PostgreSQL tasks schema for the experimental backend.

CREATE TABLE "TASK" (
  "ID" varchar NOT NULL,
  "PRIORITY" integer NOT NULL,
  "GROUP_ID" varchar,
  "CLASS" varchar NOT NULL,
  "SIMPLE_TYPE" varchar NOT NULL,
  "PAYLOAD" varchar NOT NULL,
  "OWNER" varchar,
  "CREATED_DATE" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "LAST_MODIFIED_DATE" timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "pk__task" PRIMARY KEY ("ID")
);


CREATE INDEX "idx__tasks__owner_group_id" ON "TASK" ("OWNER", "GROUP_ID");
