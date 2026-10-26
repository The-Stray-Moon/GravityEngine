package cc.sighs.gravityengine;

import cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistenceSerializer;
import cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistenceSlot;
import cc.sighs.gravityengine.gravity.persistence.GravityPersistenceSerializer;
import cc.sighs.gravityengine.gravity.persistence.GravityPersistenceSlot;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * NeoForge data-attachment registration.
 *
 * <p>{@code BODY_ATTITUDE_PERSISTENCE} deliberately has:
 * no {@code copyOnDeath()} (death respawn starts with an empty seed),
 * no {@code sync(...)} (existing BodyAttitude packets remain the only
 * network authority), and a holder-capturing default factory so the slot can
 * read live component state at save time.</p>
 */
public final class GravityEngineAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(
                    NeoForgeRegistries.ATTACHMENT_TYPES,
                    GravityEngine.MOD_ID
            );

    public static final DeferredHolder<
            AttachmentType<?>,
            AttachmentType<BodyAttitudePersistenceSlot>
    > BODY_ATTITUDE_PERSISTENCE =
            ATTACHMENTS.register(
                    "body_attitude_persistence",
                    () -> AttachmentType
                            .builder(
                                    BodyAttitudePersistenceSlot::forHolder
                            )
                            .serialize(
                                    BodyAttitudePersistenceSerializer.INSTANCE
                            )
                            /*
                             * Deliberately:
                             * - NO copyOnDeath()
                             * - NO sync(...)
                             */
                            .build()
            );

    /**
     * Durable gravity continuity.
     *
     * <p>{@code copyOnDeath()} mirrors the long-standing supported contract
     * that gravity survives death respawn; NeoForge already copies serializable
     * attachments on non-death player replacement (End return, dimension
     * change). It is deliberately not synced: the durable record is not the
     * live assignment/application wire contract.</p>
     */
    public static final DeferredHolder<
            AttachmentType<?>,
            AttachmentType<GravityPersistenceSlot>
    > GRAVITY_PERSISTENCE =
            ATTACHMENTS.register(
                    "gravity_persistence",
                    () -> AttachmentType
                            .builder(GravityPersistenceSlot::forHolder)
                            .serialize(GravityPersistenceSerializer.INSTANCE)
                            .copyOnDeath()
                            .build()
            );

    private GravityEngineAttachments() {}
}
