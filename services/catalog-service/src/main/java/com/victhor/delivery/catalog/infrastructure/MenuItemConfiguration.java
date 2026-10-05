package com.victhor.delivery.catalog.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.catalog.application.MenuItemRepository;
import com.victhor.delivery.catalog.application.MenuItemService;
import com.victhor.delivery.catalog.application.RestaurantRepository;

@Configuration(proxyBeanMethods = false)
class MenuItemConfiguration {

    @Bean
    MenuItemService menuItemService(MenuItemRepository menuItems, RestaurantRepository restaurants) {
        return new MenuItemService(menuItems, restaurants);
    }
}
