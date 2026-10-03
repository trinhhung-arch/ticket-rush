package com.ticketrush.event;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.ticketrush.contracts.event.SectionSpec;

@Entity
@Table(name = "event_section")
class Section {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "seats_per_row", nullable = false)
    private int seatsPerRow;

    @Column(name = "price_vnd", nullable = false)
    private long priceVnd;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Section() {
    }

    Section(Event event, String code) {
        this.id = UUID.randomUUID();
        this.event = event;
        this.code = code;
    }

    void apply(SectionSpec spec, int sortOrder) {
        this.name = spec.name();
        this.rowCount = spec.rows();
        this.seatsPerRow = spec.seatsPerRow();
        this.priceVnd = spec.priceVnd();
        this.sortOrder = sortOrder;
    }

    String code() {
        return code;
    }

    SectionSpec toSpec() {
        return new SectionSpec(code, name, rowCount, seatsPerRow, priceVnd);
    }
}
