package org.cloudfoundry.identity.uaa.alias;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.UUID;

import org.cloudfoundry.identity.uaa.EntityWithAlias;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.lang.Nullable;

public abstract class EntityAliasHandlerEnsureConsistencyTest<T extends EntityWithAlias> {
    protected abstract EntityAliasHandler<T> buildAliasHandler(final boolean aliasEntitiesEnabled);

    protected abstract T shallowCloneEntity(final T entity);

    protected abstract T buildEntityWithAliasProperties(@Nullable final String aliasId, @Nullable final String aliasZid);

    /**
     * Change one or more properties (but neither 'aliasId' nor 'aliasZid') of the given entity.
     */
    protected abstract void changeNonAliasProperties(final T entity);

    protected abstract void arrangeZoneDoesNotExist(final String zoneId);

    /**
     * Check whether the given two entities are equal. This method is required since the {@link ScimUser} class does not
     * implement an {@code equals} method that is precise enough.
     */
    protected abstract boolean entitiesAreEqual(final T entity1, final T entity2);

    protected final String customZoneId = UUID.randomUUID().toString();

    private abstract class Base {
        protected EntityAliasHandler<T> aliasHandler;

        @BeforeEach
        final void setUp() {
            final boolean aliasEntitiesEnabled = isAliasFeatureEnabled();
            this.aliasHandler = buildAliasHandler(aliasEntitiesEnabled);
        }

        protected abstract boolean isAliasFeatureEnabled();
    }

    private abstract class NoExistingAliasBase extends Base {
        @Test
        final void shouldIgnore_AliasZidEmptyInOriginalEntity() {
            final T originalEntity = buildEntityWithAliasProperties(null, null);
            final T existingEntity = shallowCloneEntity(originalEntity);
            changeNonAliasProperties(existingEntity);

            final T result = aliasHandler.ensureConsistencyOfAliasEntity(originalEntity, existingEntity);
            assertThat(entitiesAreEqual(result, originalEntity)).isTrue();
        }
    }

    protected abstract class NoExistingAlias_AliasFeatureEnabled extends NoExistingAliasBase {
        @Override
        protected final boolean isAliasFeatureEnabled() {
            return true;
        }
    }

    protected abstract class NoExistingAlias_AliasFeatureDisabled extends NoExistingAliasBase {
        @Override
        protected final boolean isAliasFeatureEnabled() {
            return false;
        }
    }

    protected abstract class ExistingAlias_AliasFeatureEnabled extends Base {
        @Override
        protected final boolean isAliasFeatureEnabled() {
            return true;
        }
    }

    protected abstract class ExistingAlias_AliasFeatureDisabled extends Base {
        @Override
        protected final boolean isAliasFeatureEnabled() {
            return false;
        }
    }

    protected static class EntityWithAliasMatcher<T extends EntityWithAlias> implements ArgumentMatcher<T> {
        private final String zoneId;
        private final String id;
        private final String aliasId;
        private final String aliasZid;

        public EntityWithAliasMatcher(final String zoneId, final String id, final String aliasId, final String aliasZid) {
            this.zoneId = zoneId;
            this.id = id;
            this.aliasId = aliasId;
            this.aliasZid = aliasZid;
        }

        @Override
        public boolean matches(final T argument) {
            return Objects.equals(id, argument.getId()) && Objects.equals(zoneId, argument.getZoneId())
                    && Objects.equals(aliasId, argument.getAliasId()) && Objects.equals(aliasZid, argument.getAliasZid());
        }
    }
}
