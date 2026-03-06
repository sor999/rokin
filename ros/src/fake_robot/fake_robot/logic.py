import math

class FakeRobotLogic:
    def __init__(self, x=0.0, y=0.0, speed=1.0, battery=100.0, battery_drain_rate=0.1):
        self.x = x
        self.y = y
        self.target_x = x
        self.target_y = y
        self.speed = speed
        self.battery = battery
        self.battery_drain_rate = battery_drain_rate
        self.state = 'idle' # idle, moving, arrived

    def set_target(self, x, y):
        self.target_x = x
        self.target_y = y
        if self.x != x or self.y != y:
            self.state = 'moving'

    def stop(self):
        self.target_x = self.x
        self.target_y = self.y
        self.state = 'idle'

    def update(self, dt):
        # Drain battery
        if self.battery > 0:
            self.battery -= self.battery_drain_rate * dt
            if self.battery < 0:
                self.battery = 0.0

        if self.battery <= 0:
            self.state = 'idle'
            return

        if self.state == 'moving':
            dx = self.target_x - self.x
            dy = self.target_y - self.y
            distance = math.hypot(dx, dy)
            
            move_dist = self.speed * dt
            
            if distance <= move_dist:
                self.x = self.target_x
                self.y = self.target_y
                self.state = 'arrived'
            else:
                self.x += (dx / distance) * move_dist
                self.y += (dy / distance) * move_dist

    def get_pose(self):
        return self.x, self.y

    def get_battery(self):
        return self.battery

    def get_status(self):
        return getattr(self, 'state', 'idle')
