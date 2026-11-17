package com.shev.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM conversation_state WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversationState(conversationId: String): ConversationStateEntity?

    @Query("SELECT * FROM conversation_state WHERE conversationId = :conversationId LIMIT 1")
    fun observeConversationState(conversationId: String): Flow<ConversationStateEntity?>

    @Upsert
    suspend fun saveConversationState(state: ConversationStateEntity)

    @Query("SELECT * FROM agent_tasks ORDER BY updatedAtEpochMillis DESC")
    fun observeTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTask(taskId: String): AgentTaskEntity?

    @Upsert
    suspend fun saveTask(task: AgentTaskEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: AgentTaskEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertApprovalEvidence(evidence: ApprovalEvidenceEntity)

    @Query("""
        UPDATE approval_evidence
        SET consumedAtEpochMillis = :nowMillis
        WHERE evidenceHash = :evidenceHash
          AND taskId = :taskId
          AND expectedRevision = :expectedRevision
          AND operation = :operation
          AND consumedAtEpochMillis IS NULL
          AND issuedAtEpochMillis <= :nowMillis
          AND expiresAtEpochMillis > :nowMillis
    """)
    suspend fun consumeApprovalEvidence(
        evidenceHash: String,
        taskId: String,
        expectedRevision: Long,
        operation: String,
        nowMillis: Long
    ): Int

    @Query("SELECT * FROM agent_plans WHERE taskId = :taskId LIMIT 1")
    suspend fun getAgentPlan(taskId: String): AgentPlanEntity?

    @Upsert
    suspend fun saveAgentPlan(plan: AgentPlanEntity)

    @Query("SELECT * FROM agent_plan_steps WHERE taskId = :taskId ORDER BY stepId ASC")
    suspend fun getAgentPlanSteps(taskId: String): List<AgentPlanStepEntity>

    @Query("DELETE FROM agent_plan_steps WHERE taskId = :taskId")
    suspend fun deleteAgentPlanSteps(taskId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAgentPlanSteps(steps: List<AgentPlanStepEntity>)

    @Query("""
        UPDATE agent_tasks
        SET revision = :newRevision, updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :taskId AND revision = :expectedRevision
    """)
    suspend fun advanceTaskRevision(
        taskId: String,
        expectedRevision: Long,
        newRevision: Long,
        updatedAtEpochMillis: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMcpInvocationAuthorizationEvidence(evidence: McpInvocationAuthorizationEntity)

    /**
     * One conditional UPDATE is the atomic one-use claim. Every persisted binding
     * field must match the presented authorization and the expected current action.
     */
    @Query("""
        UPDATE mcp_invocation_authorizations
        SET consumedAtEpochMillis = :nowMillis
        WHERE nonceDigest = :nonceDigest
          AND invocationId = :invocationId
          AND taskId = :taskId
          AND taskRevision = :taskRevision
          AND planRevision = :planRevision
          AND stepId = :stepId
          AND stepRevision = :stepRevision
          AND profileId = :profileId
          AND endpoint = :endpoint
          AND nativeToolId = :nativeToolId
          AND remoteToolName = :remoteToolName
          AND argumentsSha256 = :argumentsSha256
          AND issuedAtEpochMillis = :issuedAtEpochMillis
          AND expiresAtEpochMillis = :expiresAtEpochMillis
          AND consumedAtEpochMillis IS NULL
          AND EXISTS (
              SELECT 1 FROM agent_tasks current_task
              WHERE current_task.id = :taskId
                AND current_task.revision = :taskRevision
          )
          AND EXISTS (
              SELECT 1 FROM agent_plans current_plan
              WHERE current_plan.taskId = :taskId
                AND current_plan.planRevision = :planRevision
          )
          AND EXISTS (
              SELECT 1 FROM agent_plan_steps current_step
              WHERE current_step.taskId = :taskId
                AND current_step.stepId = :stepId
                AND current_step.planRevision = :planRevision
                AND current_step.stepRevision = :stepRevision
          )
          AND issuedAtEpochMillis <= :nowMillis
          AND expiresAtEpochMillis > :nowMillis
    """)
    suspend fun consumeMcpInvocationAuthorization(
        nonceDigest: String,
        invocationId: String,
        taskId: String,
        taskRevision: Long,
        planRevision: Long,
        stepId: String,
        stepRevision: Long,
        profileId: String,
        endpoint: String,
        nativeToolId: String,
        remoteToolName: String,
        argumentsSha256: String,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        nowMillis: Long
    ): Int

    @Query("DELETE FROM mcp_invocation_authorizations WHERE expiresAtEpochMillis <= :nowMillis")
    suspend fun deleteExpiredMcpInvocationAuthorizations(nowMillis: Long): Int

    @Query("DELETE FROM approval_evidence WHERE evidenceHash = :evidenceHash")
    suspend fun deleteApprovalEvidence(evidenceHash: String): Int

    @Query("DELETE FROM approval_evidence WHERE expiresAtEpochMillis <= :nowMillis")
    suspend fun deleteExpiredApprovalEvidence(nowMillis: Long): Int

    @Query("SELECT * FROM approval_evidence WHERE evidenceHash = :evidenceHash LIMIT 1")
    suspend fun getApprovalEvidence(evidenceHash: String): ApprovalEvidenceEntity?

    @Query("SELECT COUNT(*) FROM approval_evidence")
    suspend fun countApprovalEvidence(): Int

    @Query("SELECT * FROM agent_events ORDER BY createdAtEpochMillis DESC")
    fun observeEvents(): Flow<List<AgentEventEntity>>

    @Query("SELECT * FROM agent_events WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findEventByIdempotencyKey(key: String): AgentEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendEvent(event: AgentEventEntity)

    @Query("SELECT * FROM capability_grants ORDER BY createdAtEpochMillis DESC")
    fun observeCapabilityGrants(): Flow<List<CapabilityGrantEntity>>

    @Query("SELECT * FROM capability_grants WHERE revokedAtEpochMillis IS NULL AND (expiresAtEpochMillis IS NULL OR expiresAtEpochMillis > :nowMillis)")
    suspend fun getActiveCapabilityGrants(nowMillis: Long): List<CapabilityGrantEntity>

    @Upsert
    suspend fun saveCapabilityGrant(grant: CapabilityGrantEntity)

    @Query("UPDATE capability_grants SET revokedAtEpochMillis = :revokedAtMillis WHERE grantId = :grantId AND revokedAtEpochMillis IS NULL")
    suspend fun revokeCapabilityGrant(grantId: String, revokedAtMillis: Long): Int

    @Query("SELECT * FROM workspace_layouts WHERE layoutId = :layoutId LIMIT 1")
    fun observeWorkspaceLayout(layoutId: String): Flow<WorkspaceLayoutEntity?>

    @Upsert
    suspend fun saveWorkspaceLayout(layout: WorkspaceLayoutEntity)

    @Query("SELECT * FROM improvements ORDER BY updatedAtEpochMillis DESC")
    fun observeImprovements(): Flow<List<ImprovementEntity>>

    @Upsert
    suspend fun saveImprovement(improvement: ImprovementEntity)

    @Query("SELECT * FROM conversation_messages ORDER BY createdAtEpochMillis DESC LIMIT 100")
    fun observeConversationMessages(): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages ORDER BY createdAtEpochMillis DESC, id DESC LIMIT :limit")
    suspend fun getRecentConversationMessages(limit: Int): List<ConversationMessageEntity>

    /** Fetch only complete conversational turns so failed/pending rows cannot crowd out usable context. */
    @Query("SELECT * FROM conversation_messages WHERE status = 'complete' AND role IN ('user', 'assistant') ORDER BY createdAtEpochMillis DESC, id DESC LIMIT :limit")
    suspend fun getRecentCompletedConversationMessages(limit: Int): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_messages ORDER BY createdAtEpochMillis ASC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun getConversationMessagesPage(limit: Int, offset: Int): List<ConversationMessageEntity>

    @Query("SELECT COUNT(*) FROM conversation_messages")
    suspend fun countConversationMessages(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendConversationMessage(message: ConversationMessageEntity)

    @Query("UPDATE conversation_messages SET status = 'failed' WHERE status = 'pending'")
    suspend fun markInterruptedConversationMessagesFailed()

    @Query("UPDATE conversation_messages SET status = :status WHERE id = :id")
    suspend fun updateConversationMessageStatus(id: String, status: String)
}
