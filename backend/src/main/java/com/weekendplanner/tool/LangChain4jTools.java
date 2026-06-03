package com.weekendplanner.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.SearchResponse;
import com.weekendplanner.provider.AvailabilityProvider;
import com.weekendplanner.provider.MovieListingProvider;
import com.weekendplanner.provider.OrderExecutionProvider;
import com.weekendplanner.provider.PoiProvider;
import com.weekendplanner.provider.ReservationProvider;
import com.weekendplanner.provider.TicketProvider;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LangChain4jTools {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jTools.class);

    private final PoiProvider poiProvider;
    private final AvailabilityProvider availabilityProvider;
    private final ReservationProvider reservationProvider;
    private final TicketProvider ticketProvider;
    private final OrderExecutionProvider orderExecutionProvider;
    private final MovieListingProvider movieListingProvider;
    private final ObjectMapper objectMapper;

    public LangChain4jTools(PoiProvider poiProvider,
                            AvailabilityProvider availabilityProvider,
                            ReservationProvider reservationProvider,
                            TicketProvider ticketProvider,
                            OrderExecutionProvider orderExecutionProvider,
                            MovieListingProvider movieListingProvider,
                            ObjectMapper objectMapper) {
        this.poiProvider = poiProvider;
        this.availabilityProvider = availabilityProvider;
        this.reservationProvider = reservationProvider;
        this.ticketProvider = ticketProvider;
        this.orderExecutionProvider = orderExecutionProvider;
        this.movieListingProvider = movieListingProvider;
        this.objectMapper = objectMapper;
    }

    @Tool("搜索附近指定类别和标签的 POI 地点，返回 candidates 列表")
    public String searchNearby(
            @P("搜索类别: ACTIVITY, RESTAURANT, CINEMA, SHOPPING, HOTEL") String category,
            @P("标签过滤列表，如 child_friendly, indoor, social_dining") List<String> tags,
            @P("搜索半径，单位公里，默认3") int radiusKm) {
        try {
            List<PoiDto> results = poiProvider.searchByCategory(category, tags, radiusKm > 0 ? radiusKm : 3);
            log.info("[LangChain4jTools] searchNearby category={} tags={} radius={} → {} results",
                    category, tags, radiusKm, results.size());
            return objectMapper.writeValueAsString(new SearchResponse(results));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"searchNearby failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool("检查 POI 的可用性，返回排队时间和预订状态")
    public String checkAvailability(
            @P("POI 唯一标识") String poiId,
            @P("目标到达时间，如 14:00") String targetTime,
            @P("人数") int headcount) {
        try {
            CheckResponse response = availabilityProvider.checkAvailability(poiId, targetTime, headcount);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"checkAvailability failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool("预订餐厅座位，返回预订确认信息")
    public String reserveRestaurant(
            @P("餐厅 POI ID") String poiId,
            @P("用餐人数") int headcount,
            @P("到达时间，如 18:00") String targetTime) {
        try {
            var result = reservationProvider.reserve(poiId, headcount, targetTime, null);
            return objectMapper.writeValueAsString(Map.of(
                    "reservationId", result.reservationId(),
                    "success", result.success(),
                    "message", result.message()));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"reserveRestaurant failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool("预订活动门票或电影票")
    public String bookTickets(
            @P("POI ID") String poiId,
            @P("票数") int num,
            @P("场次时间，如 14:30") String sessionTime) {
        try {
            var result = ticketProvider.bookTickets(poiId, num, sessionTime, null);
            return objectMapper.writeValueAsString(Map.of(
                    "ticketId", result.ticketId(),
                    "success", result.success(),
                    "totalPrice", result.totalPrice(),
                    "message", result.message()));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"bookTickets failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool("搜索电影排片信息")
    public String searchMovies(
            @P("影院 POI ID") String cinemaId,
            @P("影片类型，如 动画、动作") String genre,
            @P("片名关键字") String keyword,
            @P("只看该时间之后的场次，如 14:00") String afterTime) {
        try {
            List<MovieListingProvider.MovieListing> results;
            if (cinemaId != null && !cinemaId.isBlank() && afterTime != null && !afterTime.isBlank()) {
                results = movieListingProvider.searchByCinemaAndTime(cinemaId, afterTime);
            } else {
                results = movieListingProvider.search(genre, keyword);
            }
            return objectMapper.writeValueAsString(Map.of("movies", results));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"searchMovies failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool("一键执行所有订单并发送通知")
    public String executeOrderAndNotify(
            @P("订单ID列表") List<String> orderIds,
            @P("联系人标识") String contactToken) {
        try {
            var result = orderExecutionProvider.execute(orderIds, contactToken);
            return objectMapper.writeValueAsString(Map.of(
                    "orderGroupId", result.orderGroupId(),
                    "status", result.status(),
                    "message", result.message()));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"executeOrderAndNotify failed: " + e.getMessage() + "\"}";
        }
    }
}
