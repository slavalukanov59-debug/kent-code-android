"""
KENT TEST LAB - Калькулятор с обработкой ошибок
Простая программа для выполнения арифметических операций над двумя числами
"""


def get_number(prompt):
    """
    Получить число от пользователя с обработкой ошибок ввода
    
    Args:
        prompt (str): Текст приглашения для ввода
        
    Returns:
        float: Введённое число
        
    Raises:
        ValueError: Если ввод не является числом
    """
    while True:
        try:
            value = input(prompt)
            return float(value)
        except ValueError:
            print(f"Ошибка: '{value}' не является числом. Попробуйте снова.")
        except EOFError:
            raise ValueError("Ввод прерван")


def add(a, b):
    """Сложение двух чисел"""
    return a + b


def subtract(a, b):
    """Вычитание второго числа из первого"""
    return a - b


def multiply(a, b):
    """Умножение двух чисел"""
    return a * b


def divide(a, b):
    """
    Деление первого числа на второе
    
    Args:
        a (float): Делимое
        b (float): Делитель
        
    Returns:
        float: Результат деления
        
    Raises:
        ZeroDivisionError: Если делитель равен нулю
    """
    if b == 0:
        raise ZeroDivisionError("Деление на ноль невозможно")
    return a / b


def power(a, b):
    """Возведение первого числа в степень второго"""
    try:
        return a ** b
    except OverflowError:
        raise OverflowError("Результат слишком большой для вычисления")


def calculate(a, b, operation):
    """
    Выполнить арифметическую операцию над двумя числами
    
    Args:
        a (float): Первое число
        b (float): Второе число
        operation (str): Операция (+, -, *, /, **)
        
    Returns:
        float: Результат операции
        
    Raises:
        ValueError: Если операция не поддерживается
        ZeroDivisionError: Если происходит деление на ноль
        OverflowError: Если результат слишком большой
    """
    operations = {
        '+': add,
        '-': subtract,
        '*': multiply,
        '/': divide,
        '**': power
    }
    
    if operation not in operations:
        raise ValueError(f"Неподдерживаемая операция: {operation}")
    
    return operations[operation](a, b)


def main():
    """Главная функция программы"""
    print("=" * 50)
    print("KENT TEST LAB - Калькулятор")
    print("=" * 50)
    print("Доступные операции: +, -, *, /, **")
    print("Для выхода введите 'exit'\n")
    
    while True:
        try:
            # Получение операции
            operation = input("Введите операцию (+, -, *, /, **): ").strip()
            
            if operation.lower() == 'exit':
                print("Завершение работы...")
                break
            
            if operation not in ['+', '-', '*', '/', '**']:
                print(f"Ошибка: Операция '{operation}' не поддерживается\n")
                continue
            
            # Получение чисел
            num1 = get_number("Введите первое число: ")
            num2 = get_number("Введите второе число: ")
            
            # Выполнение вычисления
            result = calculate(num1, num2, operation)
            
            # Вывод результата
            print(f"\nРезультат: {num1} {operation} {num2} = {result}\n")
            print("-" * 50)
            
        except ZeroDivisionError as e:
            print(f"Ошибка: {e}\n")
        except OverflowError as e:
            print(f"Ошибка: {e}\n")
        except ValueError as e:
            print(f"Ошибка: {e}\n")
            break
        except KeyboardInterrupt:
            print("\n\nПрограмма прервана пользователем")
            break
        except Exception as e:
            print(f"Непредвиденная ошибка: {e}\n")


if __name__ == "__main__":
    main()
