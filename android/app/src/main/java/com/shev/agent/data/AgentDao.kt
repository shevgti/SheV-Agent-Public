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

    @Query("SELECT * FROM agent_task_steps ORDER BY taskId ASC, position ASC")
    fun observeAllTaskSteps(): Flow<List<AgentTaskStepEntity>>

    @Query("SELECT * FROM agent_task_steps WHERE taskId = :taskId ORDER BY position ASC")
    fun observeTaskSteps(taskId: String): Flow<List<AgentTaskStepEntity>>

    @Query("SELECT * FROM agent_task_steps WHERE taskId = :taskId ORDER BY position ASC")
    suspend fun getTaskSteps(taskId: String): List<AgentTaskStepEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTaskSteps(steps: List<AgentTaskStepEntity>)

    @Query("""
        UPDATE agent_task_steps
        SET status = 'RUNNING',
            output = '',
            errorCode = NULL,
            startedAtEpochMillis = :startedAtEpochMillis,
            finishedAtEpochMillis = NULL
        WHERE taskId = :taskId
          AND stepId = :stepId
          AND planHash = :planHash
          AND status IN ('PENDING', 'INTERRUPTED', 'FAILED')
    """)
    suspend fun markTaskStepStarted(
        taskId: String,
        stepId: String,
        planHash: String,
        startedAtEpochMillis: Long
    ): Int

    @Query("""
        UPDATE agent_task_steps
        SET status = :status,
            output = :output,
            errorCode = :errorCode,
            finishedAtEpochMillis = :finishedAtEpochMillis
        WHERE taskId = :taskId
          AND stepId = :stepId
          AND planHash = :planHash
          AND status = 'RUNNING'
    """)
    suspend fun markTaskStepFinished(
        taskId: String,
        stepId: String,
        planHash: String,
        status: String,
        output: String,
        errorCode: String?,
        finishedAtEpochMillis: Long
    ): Int

    @Query("""
        UPDATE agent_task_steps
        SET status = 'INTERRUPTED',
            finishedAtEpochMillis = :finishedAtEpochMillis
        WHERE taskId = :taskId
          AND planHash = :planHash
          AND status = 'RUNNING'
    """)
    suspend fun markRunningTaskStepsInterrupted(
        taskId: String,
        planHash: String,
        finishedAtEpochMillis: Long
    ): Int

    @Query("SELECT * FROM agent_tasks WHERE phase = :phase ORDER BY updatedAtEpochMillis ASC")
    suspend fun getTasksByPhase(phase: String): List<AgentTaskEntity>

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
          AND planHash = :planHash
          AND consumedAtEpochMillis IS NULL
          AND issuedAtEpochMillis <= :nowMillis
          AND expiresAtEpochMillis > :nowMillis
    """)
    suspend fun consumeApprovalEvidence(
        evidenceHash: String,
        taskId: String,
        expectedRevision: Long,
        operation: String,
        planHash: String,
        nowMillis: Long
    ): Int

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


    @Query("SELECT * FROM agent_skills ORDER BY updatedAtEpochMillis DESC")
    fun observeAllAgentSkills(): Flow<List<AgentSkillEntity>>

    @Query("SELECT * FROM agent_skills WHERE status = 'VERIFIED' ORDER BY updatedAtEpochMillis DESC")
    suspend fun getVerifiedAgentSkills(): List<AgentSkillEntity>

    @Query("SELECT * FROM agent_skills WHERE skillId = :skillId LIMIT 1")
    suspend fun getAgentSkill(skillId: String): AgentSkillEntity?

    @Query("SELECT * FROM agent_skills WHERE status = 'VERIFIED' ORDER BY updatedAtEpochMillis DESC")
    fun observeVerifiedAgentSkills(): Flow<List<AgentSkillEntity>>

    @Upsert
    suspend fun saveAgentSkill(skill: AgentSkillEntity)

    @Query("UPDATE agent_skills SET status = 'DISABLED', updatedAtEpochMillis = :nowMillis WHERE skillId = :skillId")
    suspend fun disableAgentSkill(skillId: String, nowMillis: Long): Int

    @Query("SELECT * FROM agent_definitions ORDER BY updatedAtEpochMillis DESC")
    fun observeAgentDefinitions(): Flow<List<AgentDefinitionEntity>>

    @Query("SELECT * FROM agent_definitions WHERE agentId = :agentId LIMIT 1")
    suspend fun getAgentDefinition(agentId: String): AgentDefinitionEntity?

    @Upsert
    suspend fun saveAgentDefinition(definition: AgentDefinitionEntity)
}
