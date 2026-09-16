package cc.sighs.gravityengine;

import cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistenceSerializer;
import cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistenceSlot;
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

    private GravityEngineAttachments() {}
}
