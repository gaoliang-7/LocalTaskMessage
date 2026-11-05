package com.example.localtaskmessagelearning;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createOrder(@Valid @RequestBody CreateOrderRequest request) {

        orderService.createOrder(request);

        return Map.of("orderNo", request.orderNo());

    }

}
