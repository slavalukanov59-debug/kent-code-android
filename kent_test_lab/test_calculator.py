"""
Автоматические тесты для калькулятора KENT TEST LAB
Используется встроенный модуль unittest
"""

import unittest
import sys
from calculator import add, subtract, multiply, divide, power, calculate


class TestBasicOperations(unittest.TestCase):
    """Тесты базовых арифметических операций"""
    
    def test_add_positive_numbers(self):
        """Тест сложения положительных чисел"""
        self.assertEqual(add(5, 3), 8)
        self.assertEqual(add(10.5, 2.5), 13.0)
    
    def test_add_negative_numbers(self):
        """Тест сложения отрицательных чисел"""
        self.assertEqual(add(-5, -3), -8)
        self.assertEqual(add(-10, 5), -5)
    
    def test_subtract(self):
        """Тест вычитания"""
        self.assertEqual(subtract(10, 3), 7)
        self.assertEqual(subtract(5, 10), -5)
        self.assertEqual(subtract(-5, -3), -2)
    
    def test_multiply(self):
        """Тест умножения"""
        self.assertEqual(multiply(5, 3), 15)
        self.assertEqual(multiply(-5, 3), -15)
        self.assertEqual(multiply(0, 100), 0)
        self.assertEqual(multiply(2.5, 4), 10.0)
    
    def test_divide(self):
        """Тест деления"""
        self.assertEqual(divide(10, 2), 5)
        self.assertEqual(divide(15, 3), 5)
        self.assertAlmostEqual(divide(7, 3), 2.333333, places=5)
    
    def test_power(self):
        """Тест возведения в степень"""
        self.assertEqual(power(2, 3), 8)
        self.assertEqual(power(5, 2), 25)
        self.assertEqual(power(10, 0), 1)
        self.assertEqual(power(2, -1), 0.5)


class TestErrorHandling(unittest.TestCase):
    """Тесты обработки ошибок"""
    
    def test_divide_by_zero(self):
        """Тест деления на ноль"""
        with self.assertRaises(ZeroDivisionError) as context:
            divide(10, 0)
        self.assertIn("Деление на ноль", str(context.exception))
    
    def test_power_overflow(self):
        """Тест переполнения при возведении в степень"""
        with self.assertRaises(OverflowError):
            power(10, 100000)
    
    def test_invalid_operation(self):
        """Тест некорректной операции"""
        with self.assertRaises(ValueError) as context:
            calculate(5, 3, '%')
        self.assertIn("Неподдерживаемая операция", str(context.exception))


class TestCalculateFunction(unittest.TestCase):
    """Тесты функции calculate"""
    
    def test_calculate_addition(self):
        """Тест calculate с операцией сложения"""
        self.assertEqual(calculate(5, 3, '+'), 8)
    
    def test_calculate_subtraction(self):
        """Тест calculate с операцией вычитания"""
        self.assertEqual(calculate(10, 4, '-'), 6)
    
    def test_calculate_multiplication(self):
        """Тест calculate с операцией умножения"""
        self.assertEqual(calculate(7, 6, '*'), 42)
    
    def test_calculate_division(self):
        """Тест calculate с операцией деления"""
        self.assertEqual(calculate(20, 4, '/'), 5)
    
    def test_calculate_power(self):
        """Тест calculate с операцией возведения в степень"""
        self.assertEqual(calculate(3, 4, '**'), 81)
    
    def test_calculate_with_floats(self):
        """Тест calculate с дробными числами"""
        self.assertAlmostEqual(calculate(5.5, 2.5, '+'), 8.0)
        self.assertAlmostEqual(calculate(10.5, 3.5, '-'), 7.0)
        self.assertAlmostEqual(calculate(2.5, 4.0, '*'), 10.0)


class TestEdgeCases(unittest.TestCase):
    """Тесты граничных случаев"""
    
    def test_zero_operations(self):
        """Тест операций с нулём"""
        self.assertEqual(add(0, 0), 0)
        self.assertEqual(multiply(5, 0), 0)
        self.assertEqual(subtract(0, 5), -5)
        self.assertEqual(power(0, 5), 0)
    
    def test_negative_numbers(self):
        """Тест операций с отрицательными числами"""
        self.assertEqual(calculate(-5, -3, '+'), -8)
        self.assertEqual(calculate(-10, 2, '*'), -20)
        self.assertEqual(calculate(-15, -3, '/'), 5)
    
    def test_large_numbers(self):
        """Тест операций с большими числами"""
        self.assertEqual(calculate(1000000, 2000000, '+'), 3000000)
        self.assertEqual(calculate(999999, 1, '-'), 999998)


def run_tests():
    """Запуск всех тестов с подробным выводом"""
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    
    # Загрузка всех тестов
    suite.addTests(loader.loadTestsFromTestCase(TestBasicOperations))
    suite.addTests(loader.loadTestsFromTestCase(TestErrorHandling))
    suite.addTests(loader.loadTestsFromTestCase(TestCalculateFunction))
    suite.addTests(loader.loadTestsFromTestCase(TestEdgeCases))
    
    # Запуск с подробным выводом
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    
    # Возврат кода выхода
    return 0 if result.wasSuccessful() else 1


if __name__ == '__main__':
    sys.exit(run_tests())
