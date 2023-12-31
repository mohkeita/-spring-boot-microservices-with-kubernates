package io.mohkeita.OrderService.service;

import io.mohkeita.OrderService.entity.Order;
import io.mohkeita.OrderService.exception.CustomException;
import io.mohkeita.OrderService.external.client.PaymentService;
import io.mohkeita.OrderService.external.client.ProductService;
import io.mohkeita.OrderService.external.request.PaymentRequest;
import io.mohkeita.OrderService.external.response.PaymentResponse;
import io.mohkeita.OrderService.external.response.ProductResponse;
import io.mohkeita.OrderService.model.OrderRequest;
import io.mohkeita.OrderService.model.OrderResponse;
import io.mohkeita.OrderService.repository.OrderRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

@Service
@Log4j2
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ProductService productService;
    @Override
    public long placeOrder(OrderRequest orderRequest) {
        log.info("Placing Order Request: {}", orderRequest);

        productService.reduceQuantity(orderRequest.getProductId(), orderRequest.getQuantity());

        log.info("Created Order with Status CREATED");
        Order order = Order.builder()
                .amount(orderRequest.getTotalAmount())
                .orderStatus("CREATED")
                .productId(orderRequest.getProductId())
                .orderDate(Instant.now())
                .quantity(orderRequest.getQuantity())
                .build();

        order = orderRepository.save(order);

        log.info("Calling Payment Service to complete the payment");

        PaymentRequest paymentRequest = PaymentRequest.builder()
                .orderId(order.getId())
                .paymentMode(orderRequest.getPaymentMode())
                .amount(orderRequest.getTotalAmount())
                .build();

        String orderStatus = null;
        try {
            paymentService.doPayment(paymentRequest);
            log.info("Payment done Successfully. Changing the order status to PLACED");
            orderStatus = "PLACED";
        } catch (Exception e) {
            log.error("Error occured in payment. Changing order status to PAYMENT_FAILED");
            orderStatus = "PAYMENT_FAILED";
        }

        order.setOrderStatus(orderStatus);
        orderRepository.save(order);

        log.info("Order Places successfully with Order Id: {}", order.getId());
        return order.getId();
    }

    @Override
    public OrderResponse getOrderDetails(long orderId) {
        log.info("Get order details for Order Id : {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(()-> new CustomException("Order not found for the order id" + orderId,
                        "NOT_FOUND", 404));

        log.info("Invoking Product service to fetch the product for id: {}", order.getProductId());

        ProductResponse productResponse =
                restTemplate.getForObject(
                        "http://PRODUCT-SERVICE/products/" + order.getProductId(),
                        ProductResponse.class
                );

        log.info("Getting payment information from the payment service");

        PaymentResponse paymentResponse = restTemplate.getForObject(
                "http://PAYMENT-SERVICE/payments/orders/"+order.getId(),
                PaymentResponse.class
        );

        OrderResponse.ProductDetails productDetails = OrderResponse.ProductDetails
                .builder()
                .productName(productResponse.getProductName())
                .productId(productResponse.getProductId())
                .build();

        OrderResponse.PaymentDetails paymentDetails =
                OrderResponse.PaymentDetails.builder()
                        .paymentId(paymentResponse.getPaymentId())
                        .paymentStatus(paymentResponse.getStatus())
                        .paymentDate(paymentResponse.getPaymentDate())
                        .paymentMode(paymentResponse.getPaymentMode())
                        .build();

        return OrderResponse.builder()
                .orderId(order.getId())
                .orderStatus(order.getOrderStatus())
                .amount(order.getAmount())
                .orderDate(order.getOrderDate())
                .productDetails(productDetails)
                .paymentDetails(paymentDetails)
                .build();
    }
}
