package com.robot.fleet.support;

import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.domain.simulation.repository.SimulationRobotRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.IdentityHashMap;
import java.util.Map;

public class SimulationJpaTestDatabase implements AutoCloseable {
    private final Map<DataSource, EntityManagerFactory> factories = new IdentityHashMap<>();

    private synchronized EntityManagerFactory factory(DataSource source) {
        return factories.computeIfAbsent(source, key -> {
            var bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(key);
            bean.setPackagesToScan("com.robot.fleet.domain.simulation.entity");
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            bean.afterPropertiesSet();
            return bean.getObject();
        });
    }

    public SimulationRepository repository(JdbcTemplate jdbc) {
        return new SimulationRepository(jdbc, new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(factory(jdbc.getDataSource())))
                .getRepository(SimulationRobotRepository.class));
    }

    public TransactionTemplate transactions(DataSource source) {
        return new TransactionTemplate(new JpaTransactionManager(factory(source)));
    }

    @Override
    public void close() {
        factories.values().forEach(EntityManagerFactory::close);
    }
}
