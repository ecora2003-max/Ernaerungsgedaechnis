package de.ernaehrung.demo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import de.ernaehrung.demo.data.local.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import java.util.UUID

/** Synthetic old files only: the same row-for-row/replay assertions on host and actual Android SQLite. */
object PublishedDatabaseMigrationFixture {
    fun verify(context: Context, fromVersion: Int, schemaText: String) = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = JSONObject(schemaText).getJSONObject("database")
        val tables = schema.getJSONArray("entities")
        val columns = (0 until tables.length()).associate { i ->
            val entity = tables.getJSONObject(i); val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }
        fun snapshot(query: (String) -> android.database.Cursor): Map<String, List<Map<String, String?>>> = columns.mapValues { (table, names) ->
            query("SELECT ${names.joinToString { "`$it`" }} FROM `$table`").use { cursor ->
                buildList { while (cursor.moveToNext()) add(names.associateWith { n -> cursor.getString(cursor.getColumnIndexOrThrow(n)) }) }
                    .sortedBy { it.toString() }
            }
        }
        var upgraded: FoodDatabase? = null
        try {
            val before = SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.setForeignKeyConstraintsEnabled(true)
                for (i in 0 until tables.length()) {
                    val entity = tables.getJSONObject(i); val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace('$'.toString() + "{TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace('$'.toString() + "{TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                schema.optJSONArray("views")?.let { views ->
                    for (i in 0 until views.length()) {
                        val view = views.getJSONObject(i)
                        old.execSQL(view.getString("createSql").replace('$'.toString() + "{VIEW_NAME}", view.getString("viewName")))
                    }
                }
                seedV1(old)
                if (fromVersion >= 2) seedV2(old)
                if (fromVersion >= 3) seedV3(old)
                if (fromVersion >= 4) seedV4(old)
                if (fromVersion >= 5) seedV5(old)
                if (fromVersion >= 6) seedV6(old)
                if (fromVersion >= 7) seedV7(old)
                if (fromVersion >= 8) seedV8(old)
                if (fromVersion >= 9) seedV9(old)
                if (fromVersion >= 10) seedV10(old)
                if (fromVersion >= 11) seedV11(old)
                if (fromVersion >= 12) seedV12(old)
                if (fromVersion >= 13) seedV13(old)
                if (fromVersion >= 14) seedV14(old)
                if (fromVersion >= 15) seedV15(old)
                if (fromVersion >= 16) seedV16(old)
                if (fromVersion >= 17) seedV17(old)
                old.version = fromVersion
                snapshot { old.rawQuery(it, null) }
            }
            assertEquals(when (fromVersion) { 1 -> 22; 2 -> 25; 3 -> 26; 4 -> 28; 5 -> 29; 6 -> 32; 7 -> 35; 8 -> 36; 9 -> 39; 10 -> 41; 13 -> 45; 14 -> 46; 15 -> 49; 16 -> 50; 17 -> 51; else -> 44 }, before.size)
            assertTrue("All old modules have representative records", before.values.all { it.isNotEmpty() })
            upgraded = Room.databaseBuilder(context, FoodDatabase::class.java, name)
                .addMigrations(FoodMigrations.MIGRATION_1_2, FoodMigrations.MIGRATION_2_3, FoodMigrations.MIGRATION_3_4, FoodMigrations.MIGRATION_4_5, FoodMigrations.MIGRATION_5_6, FoodMigrations.MIGRATION_6_7, FoodMigrations.MIGRATION_7_8, FoodMigrations.MIGRATION_8_9, FoodMigrations.MIGRATION_9_10, FoodMigrations.MIGRATION_10_11, FoodMigrations.MIGRATION_11_12, FoodMigrations.MIGRATION_12_13, FoodMigrations.MIGRATION_13_14, FoodMigrations.MIGRATION_14_15, FoodMigrations.MIGRATION_15_16, FoodMigrations.MIGRATION_16_17, FoodMigrations.MIGRATION_17_18).build()
            val sql = upgraded.openHelper.writableDatabase // Room validates the complete migrated schema against v18.
            assertEquals(before, snapshot { sql.query(it) })
            sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            assertEquals(18, sql.version)
            assertEquals(if (fromVersion >= 15) 2 else 0, upgraded.nutritionGoalDao().versions().size)
            assertEquals(if (fromVersion >= 15) 2 else 0, upgraded.nutritionGoalDao().values().size)
            assertEquals(if (fromVersion >= 16) 2 else 0,upgraded.personalRestrictionDao().history().size)
            assertEquals(if (fromVersion >= 17) 1 else 0, upgraded.recipeFavoriteDao().history().size)
            assertTrue(upgraded.storageLocationReviewDao().history().isEmpty())
            assertEquals(if (fromVersion >= 13) 1 else 0, upgraded.foodDao().batchReviewsAt(Long.MAX_VALUE).size)
            val capacityHistory = upgraded.storageCapacityDao().history()
            assertEquals(if (fromVersion >= 14) 1 else 0, capacityHistory.size)
            if (fromVersion >= 14) {
                assertEquals("capacity-v14-review", capacityHistory.single().id)
                assertEquals("-18,000000000000000001", capacityHistory.single().temperatureCelsiusText)
                assertEquals(0L, capacityHistory.single().occupiedMl)
            }
            if (fromVersion >= 12) assertEquals("measure", upgraded.bodyDao().versions().single { it.id == "body-v12-linked" }.legacyOriginId)
            assertEquals("No new paid price is inferred during migration", if (fromVersion < 8) 0 else 1, upgraded.foodDao().allPaidPrices().size)
            assertEquals(if (fromVersion >= 9) 1 else 0, upgraded.healthDao().history("com.sec.android.app.shealth").size)
            if (fromVersion < 9) assertNull(upgraded.healthDao().state("com.sec.android.app.shealth"))
            else {
                assertEquals(0L, upgraded.healthDao().history("com.sec.android.app.shealth").single().steps)
                assertNull(upgraded.healthDao().history("com.sec.android.app.shealth").single().startOffsetSeconds)
                assertEquals("health-migration-sync", upgraded.healthDao().state("com.sec.android.app.shealth")!!.lastSuccessRunId)
            }
            assertEquals(if (fromVersion >= 12) 2 else if (fromVersion >= 10) 1 else 0, upgraded.bodyDao().versions().size)
            assertEquals(if (fromVersion >= 11) 1 else 0, upgraded.manualWorkoutDao().versions().size)
            assertEquals(if (fromVersion >= 11) 1 else 0, upgraded.manualWorkoutDao().sets().size)
            if (fromVersion >= 11) {
                val training = upgraded.manualWorkoutDao().versions().single()
                val trainingStore = FoodStore(upgraded) { 1_800_000_000_000L }
                assertEquals(training.id, trainingStore.saveManualWorkout(training.runId,
                    training.input(upgraded.manualWorkoutDao().sets(), true), null, "training", true))
                assertEquals("OPEN", trainingStore.beginManualWorkout("workout-v11-open").kind)
                trainingStore.purgeManualWorkouts("workout-v11-purge", true)
                assertEquals(training, upgraded.manualWorkoutDao().versions().single())
            }
            if (fromVersion >= 10) {
                val bodyStore = FoodStore(upgraded) { 1_800_000_000_000L }
                val original = de.ernaehrung.demo.data.fitness.BodyMeasurementInput(
                    de.ernaehrung.demo.data.fitness.BodyMetric.WEIGHT, "73,0400", "2026-10-25")
                assertEquals("body-v10-original", bodyStore.saveBodyMeasurement("body-v10-save", original, null, true))
                bodyStore.purgeBodyMeasurements("body-v10-purge", true)
                assertTrue(upgraded.bodyDao().versions().any { it.id == "body-v10-original" })
                if (fromVersion >= 12) {
                    val linked = original.copy(correctionReason = "Legacy geprüft")
                    assertEquals("body-v12-linked", bodyStore.saveBodyMeasurement("body-v12-link", linked, null, true,
                        "measure", de.ernaehrung.demo.data.fitness.LegacyBodyField.WEIGHT))
                    assertEquals(2, upgraded.bodyDao().versions().size)
                }
            }
            val dao = upgraded.foodDao()
            assertEquals(if (fromVersion < 6) 0 else 1, dao.purchases().size)
            assertEquals(if (fromVersion >= 7) 1 else 0, dao.receiptScans().size)
            assertEquals(if (fromVersion >= 7) 1 else 0, dao.aliasDecisions().size)
            val expenses = FoodStore(upgraded).expenseReport(ExpenseScope("2026-10-01", "2026-10-30"))
            assertEquals(if (fromVersion < 6) 0L else 398L, expenses.knownSubtotalCents); assertNull(expenses.totalCents)
            assertEquals(1, expenses.unconfirmedReceipts.size); assertFalse(expenses.complete)
            if (fromVersion < 4) {
                assertTrue(dao.allPlanExecutions().isEmpty())
                assertTrue(dao.allPlannedEntries().all { it.positionId == it.id })
            } else {
                assertEquals(1, dao.allPlanExecutions().size)
                assertEquals("history-entry2", dao.plannedEntry("history-entry3")!!.positionId)
                assertEquals(50_000L, FoodStore(upgraded).planExecution("history-entry3").status.knownExecuted)
                assertEquals(0L, FoodStore(upgraded).planExecution("history-entry3").status.remainingAmount)
            }
            if (fromVersion < 5) {
                assertNull(dao.prices().single().wholePack); assertNull(dao.prices().single().basisConfirmed)
                assertNull(dao.prices().single().currency); assertNull(dao.prices().single().validUntil)
                assertNull(dao.budgets().single().currency)
            } else {
                assertEquals(true, dao.prices().single().wholePack); assertEquals(true, dao.prices().single().basisConfirmed)
                assertEquals("EUR", dao.prices().single().currency); assertEquals("2026-11-05", dao.prices().single().validUntil)
                assertEquals("EUR", dao.budgets().single().currency); assertEquals(0L, dao.budgets().single().amountCents)
                assertTrue(dao.shoppingMarks("2026-10-30", "2026-11-05", "2026-10-30").single().checked)
            }
            assertEquals(200_000L, dao.prices().single().amount); assertEquals(249L, dao.prices().single().totalCents)
            if (fromVersion >= 3) {
                assertNotEquals(dao.plannedEntry("history-entry1")!!.positionId, dao.plannedEntry("history-entry2")!!.positionId)
                val legacyInput = PlanInput("Altplan", "2026-10-31", "2026-11-06", listOf(PlanEntryInput("history-entry1",
                    "2026-10-31", PlanKind.DIRECT_CONSUMPTION, "pv1", amount = 10_000, unit = BaseUnit.GRAM)), "Überarbeitet")
                assertEquals("history-v2", FoodStore(upgraded).savePlan("legacy-plan", legacyInput, "history-v1"))
                assertEquals("c8bfa1a548145f0a14c964a6bc9007851a36370fb5250275e76dd059c653da4b", dao.operation("legacy-plan")!!.fingerprint)
                assertEquals(if (fromVersion == 3) 3 else 4, dao.allPlans().size)
            }
            assertEquals(150_000L, dao.balance("batch")); assertNull(dao.batch("batch")!!.parentBatchId)
            assertNull(dao.movements("old-buy").single().occurredOn)
            assertNull(dao.version("pv1")!!.energyKcal); assertEquals(0.0, dao.version("pv2")!!.energyKcal!!, 0.0)
            val store = FoodStore(upgraded) { 1_800_000_000_000L }
            val nutrition = store.dayNutrition("2026-10-30")
            assertEquals(DayStatus.COMPLETE, nutrition.status)
            assertNull(nutrition.totals[Nutrient.ENERGY].knownAmount)
            assertEquals(0, java.math.BigDecimal("6.75").compareTo(nutrition.totals[Nutrient.PROTEIN].knownAmount))
            assertTrue(nutrition.totals.hasGaps)
            assertEquals("batch", store.receive("old-buy", BatchInput("pv1", "loc", 200_000, "2026-10-29", release = ReleaseStatus.RELEASED), true))
            assertEquals("meal", store.consume("old-eat", ConsumptionInput("pv1", "2026-10-30", "Mittag", 50_000, listOf(Allocation("batch", 50_000))), true))
            assertEquals(150_000L, dao.balance("batch")); assertEquals(1, dao.activeConsumptionCount())
            assertEquals("Legacy consumption dates must protect the chronology without inventing new movement dates", "2026-10-30", dao.latestMovementDate("batch"))
            val target = store.addLocation("new-location", "Vorrat", true)
            try {
                store.transfer("backdated", TransferInput("batch", target, 100_000, "2026-10-29", "Vor altem Verzehr"), true)
                fail("Known later v1 consumption must block backdating")
            } catch (_: IllegalArgumentException) { }
            assertNull(dao.operation("backdated")); assertEquals(150_000L, dao.balance("batch"))
            val moved = store.transfer("new-move", TransferInput("batch", target, 100_000, "2026-10-30", "Nach Upgrade"), true)
            assertEquals(50_000L, dao.balance("batch")); assertEquals(100_000L, dao.balance(moved))
            upgraded.close()
            upgraded = Room.databaseBuilder(context, FoodDatabase::class.java, name)
                .addMigrations(FoodMigrations.MIGRATION_1_2, FoodMigrations.MIGRATION_2_3, FoodMigrations.MIGRATION_3_4, FoodMigrations.MIGRATION_4_5, FoodMigrations.MIGRATION_5_6, FoodMigrations.MIGRATION_6_7, FoodMigrations.MIGRATION_7_8, FoodMigrations.MIGRATION_8_9, FoodMigrations.MIGRATION_9_10, FoodMigrations.MIGRATION_10_11, FoodMigrations.MIGRATION_11_12, FoodMigrations.MIGRATION_12_13, FoodMigrations.MIGRATION_13_14, FoodMigrations.MIGRATION_14_15, FoodMigrations.MIGRATION_15_16, FoodMigrations.MIGRATION_16_17, FoodMigrations.MIGRATION_17_18).build()
            assertEquals(100_000L, upgraded.foodDao().balance(moved))
            assertEquals(when (fromVersion) { 1 -> 4; 2 -> 8; 3 -> 9; 4 -> 10; 5 -> 13; 6 -> 16; 7 -> 19; 13 -> 21; 14, 15, 16, 17 -> 22; else -> 20 }, upgraded.foodDao().operationCount())
            val legacy = upgraded.foodDao().plan("plan")!!
            assertEquals("plan", legacy.seriesId); assertNull(legacy.title)
            val newPlan = FoodStore(upgraded).savePlan("new-plan", PlanInput("Überarbeitet", legacy.startsOn, legacy.endsOn,
                upgraded.foodDao().allPlannedEntries().filter { it.planId == "plan" }.map(PlanEntryInput::from), "Nach Migration"), legacy.id)
            assertEquals(2, upgraded.foodDao().plan(newPlan)!!.revision)
            assertEquals(EntryStatus.DRAFT, upgraded.foodDao().plan("plan")!!.status)
            assertEquals(EntryStatus.CONFIRMED, upgraded.foodDao().plan(newPlan)!!.status)
            assertEquals(150_000L - 100_000L, upgraded.foodDao().balance("batch"))
            val purchaseStore = FoodStore(upgraded) { 1_800_000_000_000L }
            val receiptId = purchaseStore.savePurchase("new-purchase", PurchaseInput("Testmarkt", "2026-10-30", 0,
                listOf(PurchaseLineInput(interpretedName = "Noch ungeklärt")), "Nach Migration ausdrücklich bezahlt"), true)
            assertEquals(0L, upgraded.foodDao().receipt(receiptId)!!.totalCents)
            assertEquals(if (fromVersion < 6) 1 else 2, upgraded.foodDao().purchases().size)
            // The old undated receipt remains unconfirmed; a new payment does not resolve it.
            assertNull(purchaseStore.expenseReport(ExpenseScope("2026-10-01", "2026-10-30")).totalCents)
            upgraded.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { upgraded?.close(); context.deleteDatabase(name) }
    }

    private fun seedV11(db: SQLiteDatabase) {
        val hash = WriteRules.fingerprint("MANUAL_WORKOUT_V1", "Krafttraining", "2026-10-25", null, null, null,
            "45,5000", "0,000", null, "Selbst eingeschätzt", null, null, null,
            listOf(listOf("Kniebeuge", "008", "40,0000", null, null)), "Datum selbst geprüft", null, "training")
        db.execSQL("INSERT INTO manual_workout_runs(id,kind,fingerprint,resultId,recordedAt) VALUES('workout-v11-save','SAVE',?,'workout-v11-version',1)", arrayOf(hash))
        db.execSQL("INSERT INTO manual_workout_runs(id,kind,fingerprint,resultId,recordedAt) VALUES('workout-v11-open','OPEN','',NULL,0),('workout-v11-purge','PURGE','',NULL,0)")
        db.execSQL("""INSERT INTO manual_workout_versions(id,seriesId,revision,previousId,legacyOriginId,runId,
            sport,localDate,timePrecision,rawDurationMinutes,durationMinutes,rawDistanceMetres,distanceMetres,
            effort,source,quality,correctionReason) VALUES('workout-v11-version','workout-v11-version',1,NULL,
            'training','workout-v11-save','Krafttraining','2026-10-25','DATE_ONLY','45,5000','45.5','0,000','0',
            'Selbst eingeschätzt','MANUAL','USER_REPORTED','Datum selbst geprüft')""")
        db.execSQL("""INSERT INTO manual_workout_sets(id,versionId,position,exercise,rawRepetitions,repetitions,
            rawLoadKg,loadKg) VALUES('workout-v11-set','workout-v11-version',0,'Kniebeuge','008',8,'40,0000','40')""")
    }

    private fun seedV17(db: SQLiteDatabase) {
        val hash = RecipeFavoriteOperations.hash(RecipeFavoriteInput("rv", null, true))
        db.execSQL("INSERT INTO recipe_favorites VALUES('favorite-v17','r','rv',1,NULL,'FAVORITE',?,2,'Europe/Berlin')", arrayOf(hash))
    }

    private fun seedV16(db: SQLiteDatabase) {
        val raw = "  ALLERGEN:SOY  "
        val reason = "Eigener unveränderter Profilgrund"
        val hash = WriteRules.fingerprint("PERSONAL_RESTRICTIONS_PROPOSE_V1",null,raw,reason)
        val proposed = PersonalRestrictionEntity("profile-v16-proposal",1,null,"profile-v16-propose","PROPOSE","PROPOSED",null,
            raw,reason,"Europe/Berlin",hash,1L)
        val binding = PersonalRestrictionSnapshot(listOf(proposed)).versionBinding(proposed.id)
        db.execSQL("INSERT INTO personal_restrictions VALUES(?,1,NULL,?,'PROPOSE','PROPOSED',NULL,?,?,?, ?,1)",
            arrayOf(proposed.id,proposed.operationId,raw,reason,"Europe/Berlin",hash))
        db.execSQL("INSERT INTO personal_restrictions VALUES('profile-v16-active',2,?,'profile-v16-confirm','CONFIRM','CONFIRMED',?,?,?,?,?,2)",
            arrayOf(proposed.id,proposed.id,raw,reason,"Europe/Berlin",WriteRules.fingerprint("PERSONAL_RESTRICTIONS_CONFIRM_V1",proposed.id,binding)))
    }

    private fun seedV15(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO nutrition_goal_runs VALUES('goal-v15-propose','PROPOSE','old-proposal-hash','STORED','goal-v15-draft',1),('goal-v15-confirm','CONFIRM','old-confirm-hash','STORED','goal-v15-active',2)")
        db.execSQL("INSERT INTO nutrition_goal_versions VALUES('goal-v15-draft',1,NULL,'goal-v15-propose','PROPOSED',NULL,'2026-10-30','Eigener Originalgrund',1,'Europe/Berlin')")
        db.execSQL("INSERT INTO nutrition_goal_versions VALUES('goal-v15-active',2,'goal-v15-draft','goal-v15-confirm','CONFIRMED','goal-v15-draft','2026-10-30','Eigener Originalgrund',2,'Europe/Berlin')")
        db.execSQL("INSERT INTO nutrition_goal_values VALUES('goal-v15-draft','PROTEIN','0,0000','12.000000000000000001'),('goal-v15-active','PROTEIN','0,0000','12.000000000000000001')")
    }

    private fun seedV14(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('capacity-v14-op','STORAGE_CAPACITY_CHECK_V1','legacy-capacity-hash',1,'capacity-v14-review')")
        db.execSQL("""INSERT INTO storage_capacity_reviews(id,locationId,revision,previousId,operationId,
            totalMl,occupiedMl,temperatureCelsiusText,reason,basisKind,addedOuterVolumeMl,source,quality,recordedAt,zoneId)
            VALUES('capacity-v14-review','loc',1,NULL,'capacity-v14-op',2000,0,'-18,000000000000000001',
            'Historische physische Prüfung','PHYSICAL_CHECK_V1',NULL,'USER','CONFIRMED',1,'Europe/Berlin')""")
    }

    private fun seedV13(db: SQLiteDatabase) {
        val hash = WriteRules.fingerprint("BATCH_REVIEW_V1", "batch", null, "2026-10-29", null, null,
            null, null, ReleaseStatus.RELEASED, "Alte explizite Notiz", "Historische Prüfung", true)
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('batch-v13-op','BATCH_REVIEW_V1',?,1,'batch-v13-review')", arrayOf(hash))
        db.execSQL("""INSERT INTO batch_reviews(id,batchId,revision,previousId,operationId,acquiredOn,bestBefore,useBy,
            openedAt,thawedAt,openedAtText,thawedAtText,`release`,safetyNote,reason,conditionsConfirmed,source,quality,recordedAt)
            VALUES('batch-v13-review','batch',1,NULL,'batch-v13-op','2026-10-29',NULL,NULL,
            NULL,NULL,NULL,NULL,'RELEASED','Alte explizite Notiz','Historische Prüfung',1,'USER','CONFIRMED',1)""")
    }

    private fun seedV12(db: SQLiteDatabase) {
        val original = WriteRules.fingerprint("BODY_MANUAL_V1", "WEIGHT", "73,0400", "2026-10-25",
            null, null, null, null, null, null, null, "UNKNOWN", null, null, "UNKNOWN", false, "Legacy geprüft", null)
        val hash = WriteRules.fingerprint("BODY_LEGACY_CORRECTION_V1", original, "measure", "WEIGHT")
        db.execSQL("INSERT INTO body_measurement_runs(id,kind,fingerprint,resultId,recordedAt) VALUES('body-v12-link','SAVE',?,'body-v12-linked',1792929600000)", arrayOf(hash))
        db.execSQL("""INSERT INTO body_measurement_versions(id,seriesId,revision,runId,metric,rawValue,decimalValue,unit,
            localDate,timePrecision,source,method,quality,excludedFromTrend,correctionReason,legacyOriginId,legacyField)
            VALUES('body-v12-linked','body-v12-linked',1,'body-v12-link','WEIGHT','73,0400','73.04','kg',
            '2026-10-25','DATE_ONLY','MANUAL','UNKNOWN','UNKNOWN',0,'Legacy geprüft','measure','WEIGHT')""")
    }

    private fun seedV10(db: SQLiteDatabase) {
        val hash = "4154720f01a02f1e100d88ecee4248695133e10560738401ef03f352a972537d"
        db.execSQL("INSERT INTO body_measurement_runs(id,kind,fingerprint,resultId,recordedAt) VALUES('body-v10-save','SAVE',?,'body-v10-original',1)", arrayOf(hash))
        db.execSQL("INSERT INTO body_measurement_runs(id,kind,fingerprint,resultId,recordedAt) VALUES('body-v10-purge','PURGE','',NULL,0)")
        db.execSQL("""INSERT INTO body_measurement_versions(id,seriesId,revision,previousId,runId,metric,
            rawValue,decimalValue,unit,localDate,timePrecision,source,method,quality,excludedFromTrend)
            VALUES('body-v10-original','body-v10-original',1,NULL,'body-v10-save','WEIGHT',
            '73,0400','73.04','kg','2026-10-25','DATE_ONLY','MANUAL','UNKNOWN','UNKNOWN',0)""")
    }

    private fun seedV9(db: SQLiteDatabase) {
        db.execSQL("""INSERT INTO health_import_runs(id,sourcePackage,kind,fingerprint,recordedAt,authorizationRevision,
            capturedAt,windowStart,windowEnd,day,dayZone,dayStart,dayEnd,aggregatedSteps,changedRecords,hiddenRecords)
            VALUES('health-migration-sync','com.sec.android.app.shealth','SYNC','synthetic-health-migration',4000,1,
            3000,0,3000,'1970-01-01','UTC',0,3000,0,1,0)""")
        db.execSQL("""INSERT INTO health_import_state(sourcePackage,enabled,authorizationRevision,lastSuccessRunId,
            lastAttemptAt,lastErrorCode) VALUES('com.sec.android.app.shealth',0,2,'health-migration-sync',4000,NULL)""")
        db.execSQL("""INSERT INTO health_source_record_versions(sourcePackage,kind,externalId,revision,runId,visible,
            visibilityReason,fingerprint,startAt,endAt,sourceModifiedAt,clientRecordVersion,recordingMethod,steps)
            VALUES('com.sec.android.app.shealth','STEPS','synthetic-health-record',1,'health-migration-sync',1,
            'READ_FROM_SOURCE','synthetic-health-value',1000,2000,3000,0,0,0)""")
    }

    private fun seedV7(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v7-alias','ALIAS','synthetic-alias',1,'alias')")
        db.execSQL("INSERT INTO alias_decisions(aliasId,operationId,kind,active,reason) VALUES('alias','v7-alias','CONFIRM',1,'Bestätigt')")
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v7-scan','SCAN','synthetic-scan',1,'v7-scan')")
        db.execSQL("INSERT INTO receipt_scans(operationId,rawText,method,parserVersion,imageHash) VALUES('v7-scan','TESTMARKT','MANUAL_TEXT','1','synthetic-v7')")
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v7-resolution','SCAN_RESOLUTION','synthetic-resolution',1,'v6-receipt')")
        db.execSQL("INSERT INTO receipt_scan_resolutions(scanId,operationId,receiptId,reason) VALUES('v7-scan','v7-resolution','v6-receipt','Ausdrücklich bestätigt')")
    }

    private fun seedV8(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v8-price','HISTORICAL_PAID','synthetic-private-basis',1,'v8-private-price')")
        db.execSQL("""INSERT INTO paid_price_observations(id,operationId,purchaseOperationId,receiptLineId,
            versionId,retailerId,purchasedOn,purchaseUnits,unitPriceCents,linePaidCents,allocatedDiscountCents,
            depositPerUnitCents,basisReason,recordedAt) VALUES('v8-private-price','v8-price','v6-pay','v6-line',
            'pv1','Testmarkt','2026-10-30',1,398,398,0,NULL,'Eine Einheit unabhängig geprüft',1)""")
    }

    private fun seedV6(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO receipts(id,merchant,purchasedOn,totalCents,source,quality,recordedAt) VALUES('v6-receipt','Testmarkt','2026-10-30',398,'USER','CONFIRMED',1)")
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v6-pay','PURCHASE','confirmed-payment',1,'v6-receipt')")
        db.execSQL("INSERT INTO purchase_events(operationId,receiptId,currency,reason) VALUES('v6-pay','v6-receipt','EUR','Tatsächlich bezahlt')")
        db.execSQL("INSERT INTO receipt_lines(id,receiptId,originalText,interpretedName,versionId,totalCents,quality) VALUES('v6-line','v6-receipt','TOFU','Testtofu','pv1',398,'UNKNOWN')")
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v6-stock','RECEIPT','confirmed-stock',1,'v6-batch')")
        db.execSQL("INSERT INTO batches(id,versionId,locationId,originOperationId,acquiredOn,release) VALUES('v6-batch','pv1','loc','v6-stock','2026-10-30','UNCHECKED')")
        db.execSQL("INSERT INTO stock_movements(id,batchId,operationId,delta,kind,source,reason,recordedAt,occurredOn) VALUES('v6-move','v6-batch','v6-stock',200000,'RECEIPT','USER','Positionszugang',1,'2026-10-30')")
        db.execSQL("INSERT INTO receipt_stock_bookings(lineId,operationId,basisDescription,reason) VALUES('v6-line','v6-stock','200 g nutzbar','Position geprüft')")
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v6-status','EXPENSE_STATUS','open-expenses',1,'v6-status')")
        db.execSQL("INSERT INTO expense_status_events(operationId,startsOn,endsOn,revision,complete,dataToken,reason) VALUES('v6-status','2026-10-01','2026-10-30',1,0,'old-data','Noch offen')")
    }

    private fun seedV5(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v5-price','PRICE','price-context',1,'price')")
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('v5-budget','BUDGET','budget-context',1,'budget')")
        db.execSQL("""UPDATE price_observations SET wholePack=1,basisConfirmed=1,basisDescription='200 g nutzbar',
            validFrom='2026-10-30',validUntil='2026-11-05',currency='EUR',operationId='v5-price',recordedAt=1,reason='Packung geprüft'""")
        db.execSQL("""UPDATE budgets SET amountCents=0,quality='CONFIRMED',label='Altbudget',currency='EUR',
            operationId='v5-budget',recordedAt=1,reason='Nullbudget ausdrücklich bestätigt'""")
        db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES('old-mark','SHOPPING_MARK','old-list-check',1,'old-mark')")
        db.execSQL("""INSERT INTO shopping_marks(operationId,versionId,startsOn,endsOn,purchaseOn,offerId,checked,itemToken,revision)
            VALUES('old-mark','pv1','2026-10-30','2026-11-05','2026-10-30','price',1,'old-context',1)""")
    }

    private fun seedV4(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO plan_positions(id,seriesId,createdAt) SELECT e.id,p.seriesId,1 FROM planned_entries e JOIN plans p ON p.id=e.planId")
        db.execSQL("UPDATE planned_entries SET positionId=id")
        db.execSQL("""INSERT INTO plans(id,revision,startsOn,endsOn,status,source,recordedAt,seriesId,title,changeReason)
            VALUES('history-v3',3,'2026-10-31','2026-11-06','CONFIRMED','USER',1,'history-series','Altplan','Fortgeführt')""")
        db.execSQL("""INSERT INTO planned_entries(id,planId,date,kind,productVersionId,amount,unit,positionId)
            VALUES('history-entry3','history-v3','2026-10-31','DIRECT_CONSUMPTION','pv1',10000,'GRAM','history-entry2')""")
        db.execSQL("""INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId)
            VALUES('legacy-execution-link','PLAN_LINK','alter-geprüfter-Bezug',1,'legacy-execution-link')""")
        db.execSQL("""INSERT INTO plan_executions(operationId,actualOperationId,positionId,entryId,revision,assigned,reason)
            VALUES('legacy-execution-link','old-eat','history-entry2','history-entry2',1,1,'Abweichenden Termin und Mehrmenge bestätigt')""")
    }

    private fun seedV3(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO plan_series(id,createdAt) SELECT id,recordedAt FROM plans")
        db.execSQL("UPDATE plans SET seriesId=id")
        db.execSQL("INSERT INTO plan_series(id,createdAt) VALUES('history-series',1)")
        for (revision in 1..2) {
            db.execSQL("""INSERT INTO plans(id,revision,startsOn,endsOn,status,source,recordedAt,seriesId,title,changeReason)
                VALUES(?,?,'2026-10-31','2026-11-06','CONFIRMED','USER',1,'history-series','Altplan',?)""",
                arrayOf("history-v$revision", revision, if (revision == 1) "Erste Planung" else "Überarbeitet"))
            db.execSQL("""INSERT INTO planned_entries(id,planId,date,kind,productVersionId,amount,unit)
                VALUES(?,?,'2026-10-31','DIRECT_CONSUMPTION','pv1',10000,'GRAM')""", arrayOf("history-entry$revision", "history-v$revision"))
        }
        db.execSQL("""INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId)
            VALUES('legacy-plan','PLAN_SAVE','c8bfa1a548145f0a14c964a6bc9007851a36370fb5250275e76dd059c653da4b',1,'history-v2')""")
    }

    private fun seedV2(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO products(id,createdAt) VALUES('out-product',1)")
        db.execSQL("""INSERT INTO product_versions(id,productId,revision,name,baseUnit,nutritionBasis,source,quality,recordedAt,changeReason)
            VALUES('out-version','out-product',1,'Fertiges Altgericht','PORTION',1000,'USER','UNKNOWN',1,'Altproduktion')""")
        for ((id, kind, result) in listOf(Triple("v2-buy", "RECEIPT", "raw-batch"), Triple("v2-cook", "PRODUCTION", "out-batch"),
            Triple("v2-waste", "WASTE", "out-batch"), Triple("v2-reverse", "REVERSAL", "v2-waste"))) {
            db.execSQL("INSERT INTO operations(id,kind,fingerprint,recordedAt,resultId) VALUES(?,?,?,1,?)", arrayOf(id, kind, "old-$id", result))
        }
        db.execSQL("""INSERT INTO batches(id,versionId,locationId,originOperationId,acquiredOn,release)
            VALUES('raw-batch','pv1','loc','v2-buy','2026-10-30','RELEASED')""")
        db.execSQL("""INSERT INTO batches(id,versionId,locationId,originOperationId,acquiredOn,release,recipeVersionId)
            VALUES('out-batch','out-version','loc','v2-cook','2026-10-30','RELEASED','rv')""")
        db.execSQL("INSERT INTO recipe_output_products(recipeId,productId) VALUES('r','out-product')")
        db.execSQL("""INSERT INTO productions(operationId,recipeVersionId,outputBatchId,actualPortions,producedOn,storageNote)
            VALUES('v2-cook','rv','out-batch',6000,'2026-10-30','Überlieferter Platz-/Lagervermerk')""")
        for ((id, op, batch, delta, kind) in listOf(
            listOf("v2-in", "v2-buy", "raw-batch", "100000", "RECEIPT"),
            listOf("v2-used", "v2-cook", "raw-batch", "-100000", "PRODUCTION_INPUT"),
            listOf("v2-out", "v2-cook", "out-batch", "6000", "PRODUCTION_OUTPUT"),
            listOf("v2-discarded", "v2-waste", "out-batch", "-1000", "WASTE"),
            listOf("v2-restored", "v2-reverse", "out-batch", "1000", "CORRECTION"))) {
            db.execSQL("""INSERT INTO stock_movements(id,batchId,operationId,delta,kind,source,reason,recordedAt,occurredOn,reversesId)
                VALUES(?,?,?,?,?,'USER','Altstand',1,'2026-10-30',?)""",
                arrayOf(id, batch, op, delta.toLong(), kind, if (id == "v2-restored") "v2-discarded" else null))
        }
        db.execSQL("""INSERT INTO operation_reversals(originalOperationId,reversalOperationId,date,reason)
            VALUES('v2-waste','v2-reverse','2026-10-30','Korrektur Altstand')""")
    }

    private fun seedV1(db: SQLiteDatabase) {
        fun insert(table: String, vararg fields: Pair<String, Any?>) {
            val values = ContentValues()
            fields.forEach { (key, value) -> when (value) {
                null -> values.putNull(key)
                is String -> values.put(key, value)
                is Int -> values.put(key, value)
                is Long -> values.put(key, value)
                is Double -> values.put(key, value)
                else -> error("Unsupported fixture type")
            } }
            db.insertOrThrow(table, null, values)
        }
        for (id in listOf("p1", "p2")) insert("products", "id" to id, "createdAt" to 1L)
        for ((index, energy) in listOf(null, 0.0).withIndex()) insert("product_versions", "id" to "pv${index+1}", "productId" to "p${index+1}",
            "revision" to 1, "name" to "Altprodukt ${index+1}", "baseUnit" to "GRAM", "nutritionBasis" to 100_000L,
            "energyKcal" to energy, "proteinGrams" to 13.5, "source" to "USER", "quality" to "UNKNOWN", "recordedAt" to 1L, "changeReason" to "Altstand")
        insert("product_aliases", "id" to "alias", "productId" to "p1", "versionId" to "pv1", "text" to "TOFU", "quality" to "CONFIRMED", "source" to "USER", "recordedAt" to 1L)
        insert("storage_locations", "id" to "loc", "name" to "Kühlschrank", "confirmed" to 1)
        insert("operations", "id" to "old-buy", "kind" to "RECEIPT", "fingerprint" to "8d02a7ebb9a01eef4183bb6168845e78c389713bfde5b3d856ef93b193d7fab9", "recordedAt" to 1L, "resultId" to "batch")
        insert("operations", "id" to "old-eat", "kind" to "CONSUMPTION", "fingerprint" to "ae40d962afac4f1511084d620def75019df0968b8c57dd2d52478e1710bce3cd", "recordedAt" to 2L, "resultId" to "meal")
        insert("batches", "id" to "batch", "versionId" to "pv1", "locationId" to "loc", "originOperationId" to "old-buy", "acquiredOn" to "2026-10-29", "release" to "RELEASED")
        for ((id, delta) in listOf("old-buy" to 200_000L, "old-eat" to -50_000L)) insert("stock_movements", "id" to "movement-$id", "batchId" to "batch", "operationId" to id,
            "delta" to delta, "kind" to if (delta > 0) "RECEIPT" else "CONSUMPTION", "source" to "USER", "reason" to "Altstand", "recordedAt" to 1L)
        insert("consumption_events", "id" to "meal", "operationId" to "old-eat", "productVersionId" to "pv1", "date" to "2026-10-30", "meal" to "Mittag", "amount" to 50_000L, "source" to "USER", "quality" to "UNKNOWN")
        insert("consumption_allocations", "consumptionId" to "meal", "batchId" to "batch", "amount" to 50_000L)
        insert("diary_days", "date" to "2026-10-30", "status" to "COMPLETE", "confirmedAt" to 3L)
        insert("recipes", "id" to "r", "createdAt" to 1L)
        insert("recipe_versions", "id" to "rv", "recipeId" to "r", "revision" to 1, "name" to "Altrezept", "yieldPortions" to 1000L,
            "steps" to "Garen", "mealPrep" to 1, "quality" to "UNKNOWN", "recordedAt" to 1L, "changeReason" to "Altstand")
        insert("recipe_ingredients", "recipeVersionId" to "rv", "position" to 0, "productVersionId" to "pv1", "amount" to 100_000L, "unit" to "GRAM")
        insert("plans", "id" to "plan", "revision" to 1, "startsOn" to "2026-10-31", "endsOn" to "2026-11-06", "status" to "DRAFT", "source" to "USER", "recordedAt" to 1L)
        insert("planned_entries", "id" to "planned", "planId" to "plan", "date" to "2026-10-31", "kind" to "DIRECT_CONSUMPTION", "productVersionId" to "pv1", "amount" to 10_000L, "unit" to "GRAM")
        insert("receipts", "id" to "receipt", "merchant" to "Testmarkt", "totalCents" to 249L, "source" to "USER", "quality" to "SOURCED", "recordedAt" to 1L)
        insert("receipt_lines", "id" to "line", "receiptId" to "receipt", "originalText" to "Ungeklärt", "totalCents" to 249L, "quality" to "UNKNOWN")
        insert("price_observations", "id" to "price", "versionId" to "pv1", "observedOn" to "2026-10-30", "amount" to 200_000L, "totalCents" to 249L, "source" to "USER", "quality" to "SOURCED")
        insert("budgets", "id" to "budget", "startsOn" to "2026-10-01", "endsOn" to "2026-10-31", "quality" to "UNKNOWN")
        insert("body_measurements", "id" to "measure", "measuredAt" to 1L, "weightKg" to 80.5, "method" to "Waage", "source" to "USER", "quality" to "SOURCED", "excludedFromTrend" to 0, "recordedAt" to 1L)
        insert("training_events", "id" to "training", "occurredAt" to 1L, "sport" to "Kraft", "source" to "USER", "quality" to "SOURCED", "recordedAt" to 1L)
        insert("training_sets", "id" to "set", "trainingId" to "training", "exercise" to "Kniebeuge", "position" to 0, "repetitions" to 8, "loadKg" to 40.0)
    }
}
