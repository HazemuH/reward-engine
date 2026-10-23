package com.rewardengine.core.attribute;

import com.rewardengine.common.audit.TenantScopedEntity;
import com.rewardengine.common.error.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * One generation of the customer attribute snapshot. Rows are appended while
 * LOADING and invisible to reads until the load is activated; createdAt is when
 * loading started, activatedAt when it began serving.
 */
@Getter
@Entity
@Table(name = "snapshot_load")
public class SnapshotLoad extends TenantScopedEntity {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SnapshotLoadStatus status = SnapshotLoadStatus.LOADING;

	@Column(nullable = false)
	private long rowCount;

	private Instant activatedAt;

	void appended(int rows) {
		requireLoading();
		rowCount += rows;
	}

	void activate(Instant now) {
		requireLoading();
		status = SnapshotLoadStatus.ACTIVE;
		activatedAt = now;
	}

	void supersede() {
		status = SnapshotLoadStatus.SUPERSEDED;
	}

	void fail() {
		requireLoading();
		status = SnapshotLoadStatus.FAILED;
	}

	void requireLoading() {
		if (status != SnapshotLoadStatus.LOADING) {
			throw new BusinessRuleException("SNAPSHOT_LOAD_NOT_OPEN",
					"Snapshot load " + getId() + " is " + status + "; only a LOADING load takes rows or changes state");
		}
	}
}
