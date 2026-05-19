import { Prisma, PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();
const precisions = [1, 2, 3] as const;

async function insertTempCells(tx: Prisma.TransactionClient, precision: number) {
  await tx.$executeRaw(Prisma.sql`
    WITH source AS (
      SELECT
        "mapId",
        "id",
        "name",
        "latitude",
        "longitude",
        ROUND("latitude"::numeric, ${precision}::int)::text AS "cellLatKey",
        ROUND("longitude"::numeric, ${precision}::int)::text AS "cellLngKey",
        CONCAT(
          ROUND("latitude"::numeric, 6)::text,
          ':',
          ROUND("longitude"::numeric, 6)::text
        ) AS "exactKey"
      FROM "places"
    )
    INSERT INTO "_place_marker_cells_rebuild" (
      "mapId",
      "precision",
      "cellLatKey",
      "cellLngKey",
      "latitude",
      "longitude",
      "minLat",
      "maxLat",
      "minLng",
      "maxLng",
      "count",
      "exactKeyCount",
      "groupKey",
      "placeId",
      "placeName",
      "updatedAt"
    )
    SELECT
      "mapId",
      ${precision}::int AS "precision",
      "cellLatKey",
      "cellLngKey",
      AVG("latitude")::float8 AS "latitude",
      AVG("longitude")::float8 AS "longitude",
      MIN("latitude")::float8 AS "minLat",
      MAX("latitude")::float8 AS "maxLat",
      MIN("longitude")::float8 AS "minLng",
      MAX("longitude")::float8 AS "maxLng",
      COUNT(*)::int AS "count",
      COUNT(DISTINCT "exactKey")::int AS "exactKeyCount",
      CASE
        WHEN COUNT(DISTINCT "exactKey") = 1 THEN MIN("exactKey")
        ELSE NULL
      END AS "groupKey",
      CASE WHEN COUNT(*) = 1 THEN MIN("id") ELSE NULL END AS "placeId",
      CASE WHEN COUNT(*) = 1 THEN MIN("name") ELSE NULL END AS "placeName",
      NOW() AS "updatedAt"
    FROM source
    GROUP BY "mapId", "cellLatKey", "cellLngKey"
  `);
}

async function upsertFromTemp(tx: Prisma.TransactionClient) {
  await tx.$executeRaw(Prisma.sql`
    INSERT INTO "place_marker_cells" (
      "mapId",
      "precision",
      "cellLatKey",
      "cellLngKey",
      "latitude",
      "longitude",
      "minLat",
      "maxLat",
      "minLng",
      "maxLng",
      "count",
      "exactKeyCount",
      "groupKey",
      "placeId",
      "placeName",
      "updatedAt"
    )
    SELECT
      "mapId",
      "precision",
      "cellLatKey",
      "cellLngKey",
      "latitude",
      "longitude",
      "minLat",
      "maxLat",
      "minLng",
      "maxLng",
      "count",
      "exactKeyCount",
      "groupKey",
      "placeId",
      "placeName",
      "updatedAt"
    FROM "_place_marker_cells_rebuild"
    ON CONFLICT ("mapId", "precision", "cellLatKey", "cellLngKey")
    DO UPDATE SET
      "latitude" = EXCLUDED."latitude",
      "longitude" = EXCLUDED."longitude",
      "minLat" = EXCLUDED."minLat",
      "maxLat" = EXCLUDED."maxLat",
      "minLng" = EXCLUDED."minLng",
      "maxLng" = EXCLUDED."maxLng",
      "count" = EXCLUDED."count",
      "exactKeyCount" = EXCLUDED."exactKeyCount",
      "groupKey" = EXCLUDED."groupKey",
      "placeId" = EXCLUDED."placeId",
      "placeName" = EXCLUDED."placeName",
      "updatedAt" = EXCLUDED."updatedAt"
  `);
}

async function deleteStaleCells(tx: Prisma.TransactionClient) {
  await tx.$executeRaw(Prisma.sql`
    DELETE FROM "place_marker_cells" target
    WHERE NOT EXISTS (
      SELECT 1
      FROM "_place_marker_cells_rebuild" source
      WHERE source."mapId" = target."mapId"
        AND source."precision" = target."precision"
        AND source."cellLatKey" = target."cellLatKey"
        AND source."cellLngKey" = target."cellLngKey"
    )
    AND target."updatedAt" < COALESCE(
      (SELECT MIN("updatedAt") FROM "_place_marker_cells_rebuild"),
      NOW()
    )
  `);
}

async function main() {
  await prisma.$transaction(
    async (tx) => {
      await tx.$executeRaw(Prisma.sql`
        CREATE TEMP TABLE "_place_marker_cells_rebuild"
        (LIKE "place_marker_cells" INCLUDING DEFAULTS)
        ON COMMIT DROP
      `);

      for (const precision of precisions) {
        await insertTempCells(tx, precision);
      }

      await upsertFromTemp(tx);
      await deleteStaleCells(tx);
    },
    { timeout: 120_000 },
  );
}

main()
  .then(async () => {
    await prisma.$disconnect();
  })
  .catch(async (error) => {
    console.error(error);
    await prisma.$disconnect();
    process.exit(1);
  });
