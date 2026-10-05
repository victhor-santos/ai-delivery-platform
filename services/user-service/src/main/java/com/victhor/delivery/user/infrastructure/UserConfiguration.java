package com.victhor.delivery.user.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.victhor.delivery.user.application.UserAddressRepository;
import com.victhor.delivery.user.application.UserAddressService;
import com.victhor.delivery.user.application.UserProfileService;
import com.victhor.delivery.user.application.UserRepository;

@Configuration(proxyBeanMethods = false)
public class UserConfiguration {

    @Bean
    UserProfileService userProfiles(UserRepository users) {
        return new UserProfileService(users);
    }

    @Bean
    UserAddressService userAddresses(UserRepository users, UserAddressRepository addresses) {
        return new UserAddressService(users, addresses);
    }
}
