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
 * loading started, activatedAt when it began serving, rowCount the rows it
 * serves (counted at activation, so parallel appends never contend on this row).
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

	/**
	 * An empty or truncated load would supersede the serving one and leave
	 * customers without a snapshot.
	 */
	void requireRows(long rows, long expectedRows) {
		if (rows == 0) {
			throw new BusinessRuleException("SNAPSHOT_LOAD_EMPTY",
					"Snapshot load " + getId() + " has no rows; activating it would leave every customer without one");
		}
		if (rows != expectedRows) {
			throw new BusinessRuleException("SNAPSHOT_LOAD_INCOMPLETE",
					"Snapshot load " + getId() + " holds " + rows + " rows, the source had " + expectedRows);
		}
	}

	// requireLoading again although the service checked: this entity keeps its own transitions valid
	void activate(Instant now, long rows) {
		requireLoading();
		status = SnapshotLoadStatus.ACTIVE;
		activatedAt = now;
		rowCount = rows;
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
