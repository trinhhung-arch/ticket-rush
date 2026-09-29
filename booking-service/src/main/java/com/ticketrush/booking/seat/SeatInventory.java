package com.ticketrush.booking.seat;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.ticketrush.common.seat.Seat;

/**
 * The database side of seat state. It is the last line of defence against overselling: a seat only
 * becomes SOLD through a conditional update, whatever Redis says (NFR-AVAIL-05).
 */
@Repository
public class SeatInventory {

    private static final RowMapper<SeatRow> SEAT_ROW = (rs, rowNum) -> new SeatRow(
            rs.getString("seat_code"), rs.getString("section_code"), rs.getLong("price_vnd"),
            SeatStatus.valueOf(rs.getString("status")));

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    SeatInventory(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void addSeats(UUID eventId, List<Seat> seats) {
        int[] index = {0};
        jdbcTemplate.batchUpdate("""
                insert into seat_inventory (event_id, seat_code, seat_index, section_code, price_vnd, status)
                values (?, ?, ?, ?, ?, 'AVAILABLE')
                on conflict do nothing
                """, seats, 1_000, (ps, seat) -> {
            ps.setObject(1, eventId);
            ps.setString(2, seat.code());
            ps.setInt(3, index[0]++);
            ps.setString(4, seat.sectionCode());
            ps.setLong(5, seat.priceVnd());
        });
    }

    public List<SeatRow> findAll(UUID eventId) {
        return jdbc.sql("""
                        select seat_code, section_code, price_vnd, status from seat_inventory
                        where event_id = :eventId order by seat_index
                        """)
                .param("eventId", eventId)
                .query(SEAT_ROW)
                .list();
    }

    public List<SeatRow> findByCodes(UUID eventId, Collection<String> codes) {
        return jdbc.sql("""
                        select seat_code, section_code, price_vnd, status from seat_inventory
                        where event_id = :eventId and seat_code in (:codes) order by seat_index
                        """)
                .param("eventId", eventId)
                .param("codes", codes)
                .query(SEAT_ROW)
                .list();
    }
}
